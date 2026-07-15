package com.xilidou.jooj.search;

import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRole;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import com.xilidou.jooj.session.SessionStore;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SearchStore —— 纯 JDBC 的 SQLite + FTS5 索引层(s21 Demo 25)。
 *
 * <p>JSON 是 history 的事实源(SessionStore 写 {@code ~/.jooj/sessions/&lt;id&gt;.json}),
 * SearchStore 是衍生 view —— SearchService 在 saveHistory 同步双写,失败 warn 不挡 JSON 主流程。
 * 损坏可通过 {@link SearchService#rebuildAll(com.xilidou.jooj.session.SessionService)} 重建。
 *
 * <h3>FTS5 schema(contentful 单 virtual table)</h3>
 *
 * <pre>{@code
 * CREATE VIRTUAL TABLE fts USING fts5(
 *   content,                                          -- 唯一索引列(进 FTS5 token)
 *   session_id  UNINDEXED, msg_index   UNINDEXED,
 *   block_index UNINDEXED, role        UNINDEXED,
 *   kind        UNINDEXED, tool_name   UNINDEXED,
 *   tool_use_id UNINDEXED, saved_at    UNINDEXED,
 *   tokenize = 'unicode61 remove_diacritics 2'         -- Hermes 同款,中文按字切英文不词干
 * );
 *
 * CREATE TABLE schema_meta(version INTEGER PRIMARY KEY);
 * INSERT INTO schema_meta(version) VALUES (?);
 * }</pre>
 *
 * <p><b>不走 external content + 触发器</b>:saveHistory 整盘覆盖语义下,
 * {@code DELETE WHERE session_id=? + 批量 INSERT} 三句更清楚。
 *
 * <h3>索引粒度</h3>
 *
 * <table>
 *   <tr><th>ContentBlock</th><th>处理</th></tr>
 *   <tr><td>{@code MessageParam.content} 是 String</td><td>1 行,kind=text</td></tr>
 *   <tr><td>{@link TextBlock}</td><td>kind=text</td></tr>
 *   <tr><td>{@link ToolResultBlock}(content 是 String)</td><td>kind=tool_result, 绑 tool_use_id + tool_name</td></tr>
 *   <tr><td>{@link ToolUseBlock}</td><td>不进 token 列;但 name 透传到同 message 后续 ToolResultBlock 的 tool_name 列</td></tr>
 *   <tr><td>ThinkingBlock / UnknownBlock / 嵌套图片</td><td>跳过</td></tr>
 * </table>
 *
 * <p><b>tool_name 关联</b>:ToolUseBlock 在 assistant message,ToolResultBlock 在下一条 user message
 * → 抽取层维护 {@code toolUseId → toolName} map 跨 message 累计。
 *
 * <h3>并发</h3>
 *
 * <p>单 {@link Connection} + WAL 模式。jooj 是单进程,WAL 让"读不阻塞写,写不阻塞读"。
 * SQLite 内部对单连接的写串行化(no concurrent writes within a connection),
 * 但 SessionService.saveHistory 已经在 indexLock 下顺序调,SearchStore 不需要再自己加锁。
 *
 * <p><b>不暴露 Connection</b>:Connection 在构造器里建,close() 关。所有 SQL 通过本类公共方法走。
 */
@Slf4j
public class SearchStore implements AutoCloseable {

    private final SearchConfig config;
    private final Connection conn;

    public SearchStore(SearchConfig config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.config = config;
        try {
            // 父目录确保存在(JoojHome 已建 ~/.jooj/,但用户可能配独立 dbPath)
            Path parent = config.dbPath().getParent();
            if (parent != null) Files.createDirectories(parent);

            // jdbc:sqlite:<absolute-path>。xerial sqlite-jdbc 自动加载 native lib。
            String url = "jdbc:sqlite:" + config.dbPath().toAbsolutePath();
            this.conn = DriverManager.getConnection(url);

            // WAL:读不阻塞写,写不阻塞读 —— 单进程多 channel 并发够用
            // busy_timeout:等待 lock 的最长时间(ms),WAL 模式下基本用不到
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=" + config.busyTimeoutMs());
                st.execute("PRAGMA synchronous=NORMAL");  // WAL 推荐,比 FULL 快很多,够安全
            }
            ensureSchema();
        } catch (Exception e) {
            throw new IllegalStateException("SearchStore init failed: " + e.getMessage(), e);
        }
    }

    /** 启动期建表(若不存在)+ 校验 schema_meta.version。version 不一致按 startupCheck 策略处理。 */
    void ensureSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            // schema_meta 总建,空表也合法
            st.execute("CREATE TABLE IF NOT EXISTS schema_meta(version INTEGER PRIMARY KEY)");
        }

        Integer existing = readSchemaVersion();
        boolean needsCreate;
        if (existing == null) {
            // 全新 db,直接建
            needsCreate = true;
        } else if (existing == config.schemaVersion()) {
            // 已经匹配
            return;
        } else {
            // 版本不一致 —— 走 startupCheck 策略
            String mode = config.startupCheck();
            log.warn("[Search] schema version mismatch: db={} expected={} (mode={})",
                    existing, config.schemaVersion(), mode);
            if ("none".equals(mode)) {
                // 不查也不重建,接受现有 schema(可能跑出奇怪错)
                return;
            }
            // light / strict 都 drop + recreate,**不自动重建数据**,提示用户必要时调 rebuildAll
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS fts");
                st.execute("DELETE FROM schema_meta");
            }
            needsCreate = true;
            log.warn("[Search] FTS5 table recreated empty. Call SearchService.rebuildAll() to reimport.");
        }

        if (needsCreate) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE VIRTUAL TABLE fts USING fts5(
                          content,
                          session_id  UNINDEXED,
                          msg_index   UNINDEXED,
                          block_index UNINDEXED,
                          role        UNINDEXED,
                          kind        UNINDEXED,
                          tool_name   UNINDEXED,
                          tool_use_id UNINDEXED,
                          saved_at    UNINDEXED,
                          tokenize = 'unicode61 remove_diacritics 2'
                        )
                        """);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO schema_meta(version) VALUES (?)")) {
                ps.setInt(1, config.schemaVersion());
                ps.executeUpdate();
            }
        }
    }

    private Integer readSchemaVersion() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM schema_meta LIMIT 1")) {
            return rs.next() ? rs.getInt(1) : null;
        }
    }

    // ── Index write API ──────────────────────────────────────────

    /**
     * saveHistory 一次性覆盖整 session:DELETE WHERE session_id=? + 批量 INSERT。
     *
     * <p>原子性:用一个 transaction 包住,失败回滚。
     */
    public void replaceSession(String sessionId, List<LlmMessage> history, Instant savedAt) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        long savedAtMillis = (savedAt != null ? savedAt : Instant.now()).toEpochMilli();
        try {
            conn.setAutoCommit(false);
            try {
                deleteSessionInternal(sessionId);
                if (history != null && !history.isEmpty()) {
                    insertHistory(sessionId, history, savedAtMillis);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.warn("[Search] replaceSession({}) failed: {}", sessionId, e.toString());
            throw new IllegalStateException("replaceSession failed: " + e.getMessage(), e);
        }
    }

    /**
     * s22 P3-b:appendOne —— 事件驱动的增量索引入口。
     *
     * <p>每次 TranscriptEvent 派发时调一次,直接 INSERT 一行到 FTS。
     * 跟 {@link #replaceSession} 的区别:
     * <ul>
     *   <li>没有 DELETE 前置(单行不做覆盖,累积增量索引)</li>
     *   <li>没有 msg_index / block_index 概念(事件流没有"第几条")—— 都写 -1 占位</li>
     *   <li>kind 固定为 "text"(scheduled / user / assistant 都是纯文本 event)</li>
     * </ul>
     *
     * <p><b>去重</b>:调用方 (SearchService) 用 eventId LRU 保证同一事件只调一次。
     *
     * @param sessionId session id
     * @param role      "user" / "scheduled" / "assistant"
     * @param content   干净原文
     * @param savedAt   时间戳(用于 FTS 查询按时间排序)
     */
    public void appendOne(String sessionId, String role, String content, Instant savedAt) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (content == null || content.isEmpty()) return;
        long savedAtMillis = (savedAt != null ? savedAt : Instant.now()).toEpochMilli();
        String sql = """
                INSERT INTO fts(content, session_id, msg_index, block_index,
                                role, kind, tool_name, tool_use_id, saved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tokenizeForIndex(content));
            ps.setString(2, sessionId);
            ps.setInt(3, -1);
            ps.setInt(4, -1);
            ps.setString(5, role);
            ps.setString(6, "text");
            ps.setNull(7, java.sql.Types.VARCHAR);
            ps.setNull(8, java.sql.Types.VARCHAR);
            ps.setLong(9, savedAtMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("[Search] appendOne({}, role={}) failed: {}",
                    sessionId, role, e.toString());
            throw new IllegalStateException("appendOne failed: " + e.getMessage(), e);
        }
    }

    private void insertHistory(String sessionId, List<LlmMessage> history, long savedAtMillis)
            throws SQLException {
        // toolCallId → toolName 反查表(跨 message 累计):assistant 一条 message 里的 LlmToolCall
        // 把 (id → name) 登记;下一条 TOOL message 里 LlmToolResult 用 toolCallId 反查 toolName
        Map<String, String> toolCallIdToName = new HashMap<>();
        String sql = """
                INSERT INTO fts(content, session_id, msg_index, block_index,
                                role, kind, tool_name, tool_use_id, saved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int msgIdx = 0; msgIdx < history.size(); msgIdx++) {
                LlmMessage m = history.get(msgIdx);
                if (m == null) continue;
                // canonical role serialized 是 uppercase("USER"/"ASSISTANT"/"TOOL");为保 FTS 表
                // 兼容(前端 / 老索引数据是 lowercase),这里 lowercase 存
                String role = m.getRole() == null ? null : m.getRole().name().toLowerCase();
                List<LlmContent> content = m.getContent();
                if (content == null || content.isEmpty()) continue;
                int blockIdx = 0;
                for (LlmContent c : content) {
                    if (c instanceof LlmText t) {
                        String text = t.getText();
                        if (text != null && !text.isEmpty()) {
                            ps.setString(1, tokenizeForIndex(text));
                            ps.setString(2, sessionId);
                            ps.setInt(3, msgIdx);
                            ps.setInt(4, blockIdx);
                            ps.setString(5, role);
                            ps.setString(6, "text");
                            ps.setNull(7, java.sql.Types.VARCHAR);
                            ps.setNull(8, java.sql.Types.VARCHAR);
                            ps.setLong(9, savedAtMillis);
                            ps.executeUpdate();
                        }
                    } else if (c instanceof LlmToolCall tc) {
                        // 不索引,但登记 id → name 给后续 tool_result 反查
                        if (tc.getId() != null && tc.getName() != null) {
                            toolCallIdToName.put(tc.getId(), tc.getName());
                        }
                    } else if (c instanceof LlmToolResult tr) {
                        String text = tr.getOutput();
                        if (text != null && !text.isEmpty()) {
                            String toolName = tr.getToolCallId() != null
                                    ? toolCallIdToName.get(tr.getToolCallId())
                                    : null;
                            ps.setString(1, tokenizeForIndex(text));
                            ps.setString(2, sessionId);
                            ps.setInt(3, msgIdx);
                            ps.setInt(4, blockIdx);
                            ps.setString(5, role);
                            ps.setString(6, "tool_result");
                            if (toolName != null) ps.setString(7, toolName);
                            else ps.setNull(7, java.sql.Types.VARCHAR);
                            if (tr.getToolCallId() != null) ps.setString(8, tr.getToolCallId());
                            else ps.setNull(8, java.sql.Types.VARCHAR);
                            ps.setLong(9, savedAtMillis);
                            ps.executeUpdate();
                        }
                    }
                    // LlmThinking / LlmOpaque 都跳过
                    blockIdx++;
                }
            }
        }
    }

    /**
     * 索引前预处理 —— 在 CJK 字符之间插空格,让 unicode61 把每个 CJK 字当独立 token。
     *
     * <p>SQLite unicode61 默认按 Unicode "Letter / Number" categories 切 token,但不处理
     * CJK 字符的"无空格连写"特性,导致 "微信改造日志" 整串当一个 token,搜 "微信" 不命中。
     * 简单粗暴:在每两个相邻 CJK 字符(以及 CJK 与非 CJK 边界)之间插一个空格。
     *
     * <p>查询时也要走同样转换({@link #tokenizeForQuery})保持口径一致。
     *
     * <p>Range 覆盖:
     * <ul>
     *   <li>U+4E00–U+9FFF — 中日韩统一表意文字</li>
     *   <li>U+3400–U+4DBF — 扩展 A</li>
     *   <li>U+3000–U+303F — CJK 标点</li>
     *   <li>U+3040–U+30FF — 日文假名</li>
     *   <li>U+AC00–U+D7AF — 韩文音节</li>
     * </ul>
     */
    static String tokenizeForIndex(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length() * 2);
        boolean prevCjk = false;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            boolean isCjk = isCjk(c);
            if (sb.length() > 0 && (prevCjk || isCjk) && sb.charAt(sb.length() - 1) != ' ' && c != ' ') {
                sb.append(' ');
            }
            sb.append(c);
            prevCjk = isCjk;
        }
        return sb.toString();
    }

    /** 查询字符串走同款 CJK split,保证查询口径跟索引一致。 */
    static String tokenizeForQuery(String q) {
        return tokenizeForIndex(q);
    }

    private static boolean isCjk(char c) {
        return (c >= '一' && c <= '鿿')
                || (c >= '㐀' && c <= '䶿')
                || (c >= '　' && c <= '〿')
                || (c >= '぀' && c <= 'ヿ')
                || (c >= '가' && c <= '힯');
    }

    // ── Search API ──────────────────────────────────────────────

    /** FTS5 MATCH + UNINDEXED 列 filter,返回 {@code List<SearchHit>}。 */
    public List<SearchHit> search(SearchQuery q) {
        if (q == null) throw new IllegalArgumentException("query must not be null");
        String query = q.query();
        if (query == null || query.isBlank()) {
            return List.of();
        }

        int limit = Math.min(Math.max(1, q.limit()), config.maxLimit());

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT session_id, msg_index, block_index, role, kind, tool_name, tool_use_id, saved_at, ");
        // FTS5 snippet(): 5 个参数 — table, column index (-1 = first indexed col), open, close, ellipsis, max_tokens
        sql.append("snippet(fts, 0, '<b>', '</b>', '...', 16) AS snippet ");
        sql.append("FROM fts WHERE fts MATCH ? ");
        List<Object> params = new ArrayList<>();
        params.add(escapeQuery(query));

        if (q.sessionId() != null) {
            sql.append("AND session_id = ? ");
            params.add(q.sessionId());
        }
        if (q.role() != null) {
            sql.append("AND role = ? ");
            params.add(q.role());
        }
        if (q.kind() != null) {
            sql.append("AND kind = ? ");
            params.add(q.kind());
        }
        sql.append("ORDER BY rank LIMIT ?");
        params.add(limit);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Integer pi) ps.setInt(i + 1, pi);
                else ps.setString(i + 1, p.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<SearchHit> hits = new ArrayList<>();
                while (rs.next()) {
                    hits.add(new SearchHit(
                            rs.getString("session_id"),
                            rs.getInt("msg_index"),
                            rs.getInt("block_index"),
                            rs.getString("role"),
                            rs.getString("kind"),
                            rs.getString("tool_name"),
                            rs.getString("tool_use_id"),
                            rs.getLong("saved_at"),
                            rs.getString("snippet")
                    ));
                }
                return hits;
            }
        } catch (SQLException e) {
            // FTS5 对非法表达式(unbalanced quote / etc)抛 SQLException —— 当作"无结果"处理,
            // 不让 LLM 因为表达式坏直接 crash agentLoop
            log.warn("[Search] search failed for query='{}': {}", query, e.toString());
            return List.of();
        }
    }

    /**
     * 简单转义 + CJK 切字:让 query 跟索引层走同样的 CJK 拆分,保证 "微信" 在索引里被切成
     * "微 信" 两 token、查询时也切成 "微 信" 才能命中。
     *
     * <p>FTS5 query syntax 自身允许 OR / AND / NOT / "phrase";用户/LLM 给的 bare keyword
     * 也合法。所以这里基本透传,只挡掉 SQL 字面量层的二次 quote。
     *
     * <p>注:更严格的转义(把每个 token 都用 "..." 包起来)会让 OR / AND 这种 FTS5 关键字变成纯字面量
     * 搜索 —— 失去 FTS5 表达式能力。当前选择"不转义,让 SQLException 走 warn-and-empty"路径。
     */
    private static String escapeQuery(String q) {
        return tokenizeForQuery(q.trim());
    }

    // ── Maintenance API ──────────────────────────────────────────

    public void deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            deleteSessionInternal(sessionId);
        } catch (SQLException e) {
            log.warn("[Search] deleteSession({}) failed: {}", sessionId, e.toString());
        }
    }

    private void deleteSessionInternal(String sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM fts WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        }
    }

    /** clearHistory 钩子 —— 跟 deleteSession 行为一致(SQLite 不区分"清空"和"删除")。 */
    public void clearSession(String sessionId) {
        deleteSession(sessionId);
    }

    /**
     * rebuildAll 入口:扫所有 session 的 JSON 反向 import 进 FTS5。
     * 启动期不自动调,留给 SearchService.rebuildAll(API)调。
     */
    public void rebuild(SessionStore store, List<String> sessionIds) {
        if (store == null || sessionIds == null) return;
        long savedAtMillis = Instant.now().toEpochMilli();
        for (String sid : sessionIds) {
            try {
                List<LlmMessage> hist = store.readCanonicalHistory(sid);
                replaceSession(sid, hist, Instant.ofEpochMilli(savedAtMillis));
            } catch (Exception e) {
                log.warn("[Search] rebuild({}) failed: {}", sid, e.toString());
            }
        }
    }

    /** countSession —— FTS5 表里这个 session 有多少行(strict 一致性检查用)。 */
    public int countSession(String sessionId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM fts WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("[Search] countSession({}) failed: {}", sessionId, e.toString());
            return -1;
        }
    }

    /** 总行数 —— 调试 / 检查用。 */
    public int countAll() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM fts")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.warn("[Search] countAll failed: {}", e.toString());
            return -1;
        }
    }

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            log.warn("[Search] close failed: {}", e.toString());
        }
    }
}
