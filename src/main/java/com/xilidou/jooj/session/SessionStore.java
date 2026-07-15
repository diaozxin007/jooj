package com.xilidou.jooj.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.llm.adapter.AnthropicAdapter;
import com.xilidou.jooj.llm.domain.LlmMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Session 的 IO 层 —— 整加载 / 整落盘,分离业务逻辑。
 *
 * <p>文件布局:
 * <pre>
 * ~/.jooj/sessions/
 *   ├── index.json                索引(id → metadata)
 *   ├── default.json              Web 默认 session 的 history
 *   ├── cli-default.json          CLI 固定 session
 *   ├── cron-default.json         cron 收容 session
 *   └── &lt;uuid&gt;.json               用户主动新建的 session
 * </pre>
 *
 * <p>每个 history 文件 = {@code List<LlmMessage>} 的 Jackson serialized JSON(P2 Step G)。
 *
 * <h3>P2 Step G:canonical JSON 格式</h3>
 *
 * <p>磁盘上的 history 从 Anthropic wire shape({@code MessageParam})迁到 canonical
 * vendor-neutral shape({@link LlmMessage})。切换后老 session 无法直接反序列化 ——
 * plan §三"决策:丢弃老 session"约定不做自动迁移,{@link #readCanonicalHistory}
 * 检测到 root 是 legacy shape(元素 role 字段是小写 "user"/"assistant" 而非 canonical
 * enum 的 "USER"/"ASSISTANT"/"TOOL")时 {@code log.warn} 提示用户手动清理
 * {@code ~/.jooj/sessions/},返回空 history。
 *
 * <p><b>桥接期(Steps G1 → G2)</b>:上游 caller(SessionService)仍持有
 * {@code List<MessageParam>},SessionStore 内部通过 {@link AnthropicAdapter} 完成
 * canonical ↔ wire 转换。G2 完成后上游改用 canonical,legacy API 删除。
 *
 * <p>不参与并发控制 —— 由 {@link SessionService} 在更上层用 lock 协调,
 * SessionStore 自己只关心"给一个 path 能读出 / 写入这个 list"。
 */
@Slf4j
public class SessionStore {

    /** 索引文件名(放在 sessions/ 目录里)。 */
    private static final String INDEX_FILE = "index.json";

    private final Path sessionsDir;
    private final ObjectMapper json;
    private final AnthropicAdapter adapter;

    public SessionStore(Path sessionsDir, ObjectMapper json) {
        if (sessionsDir == null) throw new IllegalArgumentException("sessionsDir must not be null");
        if (json == null) throw new IllegalArgumentException("json must not be null");
        this.sessionsDir = sessionsDir;
        this.json = json;
        this.adapter = new AnthropicAdapter(json);
    }

    public Path sessionsDir() {
        return sessionsDir;
    }

    // ── 索引 IO ────────────────────────────────────────────────

    /** 读取索引。文件不存在时返空 map(不当错误)。 */
    public Map<String, Session> readIndex() {
        Path indexPath = sessionsDir.resolve(INDEX_FILE);
        if (!Files.exists(indexPath)) {
            return new LinkedHashMap<>();
        }
        try {
            byte[] bytes = Files.readAllBytes(indexPath);
            if (bytes.length == 0) return new LinkedHashMap<>();
            List<Session> list = json.readValue(bytes, new TypeReference<List<Session>>() {
            });
            // 用 LinkedHashMap 保持创建顺序(便于前端 list 显示)
            Map<String, Session> map = new LinkedHashMap<>();
            for (Session s : list) {
                if (s != null && s.id() != null) {
                    map.put(s.id(), s);
                }
            }
            return map;
        } catch (IOException e) {
            log.warn("[Session] readIndex failed: {}", e.toString());
            return new LinkedHashMap<>();
        }
    }

    /** 写索引。整个 map 序列化成 list,保持 LinkedHashMap 的创建顺序。 */
    public void writeIndex(Map<String, Session> sessions) {
        Path indexPath = sessionsDir.resolve(INDEX_FILE);
        try {
            Files.createDirectories(sessionsDir);
            byte[] bytes = json.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(new ArrayList<>(sessions.values()));
            Files.write(indexPath, bytes);
        } catch (IOException e) {
            log.warn("[Session] writeIndex failed: {}", e.toString());
        }
    }

    // ── History IO(canonical,P2 Step G)───────────────────────

    /**
     * 读 history —— 返 canonical {@link LlmMessage} list。
     *
     * <p>文件不存在 → 空 list。文件是 legacy shape → log.warn + 返空(丢弃老 session,
     * plan §三 §一 boundary #2)。
     */
    public List<LlmMessage> readCanonicalHistory(String sessionId) {
        Path path = historyPath(sessionId);
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) return new ArrayList<>();
            JsonNode root = json.readTree(bytes);
            if (!root.isArray() || root.size() == 0) {
                return new ArrayList<>();
            }
            // Sniff:canonical role 值是 "USER"/"ASSISTANT"/"TOOL"(LlmRole enum
            // Jackson 默认序列化 uppercase);legacy 是 "user"/"assistant"。
            JsonNode first = root.get(0);
            JsonNode roleNode = first != null ? first.get("role") : null;
            String role = roleNode != null && roleNode.isTextual() ? roleNode.asText() : null;
            boolean isCanonical = role != null
                    && ("USER".equals(role) || "ASSISTANT".equals(role) || "TOOL".equals(role));
            if (!isCanonical) {
                log.warn("[Session] readCanonicalHistory({}) — legacy shape detected, discarding. "
                        + "Manually clear ~/.jooj/sessions/*.json after P2 upgrade (see project docs).",
                        sessionId);
                return new ArrayList<>();
            }
            return json.readValue(bytes, new TypeReference<List<LlmMessage>>() {
            });
        } catch (IOException e) {
            log.warn("[Session] readCanonicalHistory({}) failed: {}", sessionId, e.toString());
            return new ArrayList<>();
        }
    }

    /**
     * 写 history —— canonical {@link LlmMessage} list 直接序列化到磁盘。
     */
    public void writeCanonicalHistory(String sessionId, List<LlmMessage> history) {
        Path path = historyPath(sessionId);
        try {
            Files.createDirectories(sessionsDir);
            byte[] bytes = json.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(history != null ? history : new ArrayList<>());
            Files.write(path, bytes);
        } catch (IOException e) {
            log.warn("[Session] writeCanonicalHistory({}) failed: {}", sessionId, e.toString());
        }
    }

    // ── Legacy API(桥接期,Step G2 删)─────────────────────────

    /**
     * @deprecated Step G1 桥接层 —— 内部走 canonical,通过 {@link AnthropicAdapter#messagesToWire}
     * 把 canonical list 桥回 wire {@link MessageParam} 返给 caller。
     * Step G2 上游 caller 改用 {@link #readCanonicalHistory} 后本方法删除。
     */
    @Deprecated
    public List<MessageParam> readHistory(String sessionId) {
        List<LlmMessage> canonical = readCanonicalHistory(sessionId);
        if (canonical.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(adapter.messagesToWire(canonical));
    }

    /**
     * @deprecated Step G1 桥接层 —— 内部通过 {@link AnthropicAdapter#messageToDomain}
     * 把 wire {@link MessageParam} 逐条桥回 canonical {@link LlmMessage} 落盘。
     * Step G2 上游 caller 改用 {@link #writeCanonicalHistory} 后本方法删除。
     */
    @Deprecated
    public void writeHistory(String sessionId, List<MessageParam> history) {
        if (history == null || history.isEmpty()) {
            writeCanonicalHistory(sessionId, new ArrayList<>());
            return;
        }
        List<LlmMessage> canonical = new ArrayList<>(history.size());
        for (MessageParam m : history) {
            if (m == null) continue;
            canonical.add(adapter.messageToDomain(m));
        }
        writeCanonicalHistory(sessionId, canonical);
    }

    /** 删 history 文件(idempotent,文件不存在不抛)。 */
    public void deleteHistory(String sessionId) {
        Path path = historyPath(sessionId);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("[Session] deleteHistory({}) failed: {}", sessionId, e.toString());
        }
    }

    private Path historyPath(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        // 防路径注入:只允许 [a-zA-Z0-9_-] 字符
        if (!sessionId.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("invalid sessionId: " + sessionId);
        }
        return sessionsDir.resolve(sessionId + ".json");
    }
}
