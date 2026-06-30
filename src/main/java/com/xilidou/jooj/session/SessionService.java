package com.xilidou.jooj.session;

import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.search.SearchService;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Session 业务层 —— create / list / get / delete / loadHistory / saveHistory。
 *
 * <p>跟 {@link SessionStore} 的分工:
 * <ul>
 *   <li>SessionStore 管 IO(读 index / 读写 &lt;id&gt;.json)</li>
 *   <li>SessionService 管业务(校验 / 上限 / 时间戳 / message count 计算)</li>
 * </ul>
 *
 * <p>不自己 {@code @Component},由 {@code SessionConfiguration} 装配 ——
 * 跟 Memory / Tasks 保持一致,model 层 framework-agnostic,测试可 {@code new} 不依赖容器。
 *
 * <h3>启动期 ensure</h3>
 *
 * <p>{@link #ensureBootstrap} 应该在容器装配完成后被调一次,确保三个特殊 session
 * ({@code default}/{@code cli-default}/{@code cron-default})存在。
 */
@Slf4j
public class SessionService {

    /** session 数量上限(超过 create 返 4xx)。 */
    public static final int MAX_SESSIONS = 50;

    /** title 时间戳格式。 */
    private static final DateTimeFormatter TITLE_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final SessionStore store;

    /**
     * s21 Demo 25:SQLite + FTS5 search index 钩子。可空(测试 / CLI 单测可选)。
     * SessionService 不强依赖 SearchService —— 老 1 参 ctor 仍合法,内部 null 守卫。
     */
    private final SearchService searchService;

    /** 内存索引(单例,启动期从 {@link SessionStore#readIndex()} 加载)。 */
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    /** 写 index 时的互斥锁。CRUD 都串行,简单可靠。 */
    private final ReentrantLock indexLock = new ReentrantLock();

    /** in-memory history 缓存:同 session 多次访问不重复读盘。 */
    private final Map<String, List<MessageParam>> historyCache = new ConcurrentHashMap<>();

    public SessionService(SessionStore store) {
        this(store, null);
    }

    /**
     * s21 Demo 25 加 2 参构造器:同时接 SearchService。null 安全(老调用方仍走 1 参委托)。
     */
    public SessionService(SessionStore store, SearchService searchService) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        this.store = store;
        this.searchService = searchService;
    }

    /**
     * 启动期一次性调用 —— 从盘上读 index,确保三个特殊 session 存在。
     * 由 {@code SessionConfiguration#sessionService} bean 在 {@link jakarta.annotation.PostConstruct}
     * 阶段触发,或者由测试代码手动调一次。
     */
    public void ensureBootstrap() {
        indexLock.lock();
        try {
            sessions.clear();
            sessions.putAll(store.readIndex());
            ensureReserved(Session.DEFAULT_ID, "Web default");
            ensureReserved(Session.CLI_DEFAULT_ID, "CLI default");
            ensureReserved(Session.CRON_DEFAULT_ID, "Cron default");
            store.writeIndex(sessions);
        } finally {
            indexLock.unlock();
        }
    }

    private void ensureReserved(String id, String title) {
        if (sessions.containsKey(id)) return;
        Instant now = Instant.now();
        Session s = new Session(id, title, now, now, 0);
        sessions.put(id, s);
        log.info("[Session] bootstrapped reserved session {}", id);
    }

    // ── CRUD ────────────────────────────────────────────────────

    /**
     * 创建新 session。返回带 UUID 的 Session。
     *
     * @param title 用户给的标题(空白 → 自动 "New chat MM-dd HH:mm")
     * @throws IllegalStateException 如果已超过 {@link #MAX_SESSIONS}
     */
    public Session create(String title) {
        indexLock.lock();
        try {
            if (sessions.size() >= MAX_SESSIONS) {
                throw new IllegalStateException(
                        "Reached max sessions (" + MAX_SESSIONS + "). Please delete some.");
            }
            Instant now = Instant.now();
            String resolvedTitle = (title == null || title.isBlank())
                    ? "New chat " + TITLE_FMT.format(now)
                    : title.trim();
            String id = UUID.randomUUID().toString();
            Session s = new Session(id, resolvedTitle, now, now, 0);
            sessions.put(id, s);
            store.writeIndex(sessions);
            log.info("[Session] created {}", id);
            return s;
        } finally {
            indexLock.unlock();
        }
    }

    /**
     * 测试 / 用例驱动:用指定 ID 创建 session(若已存在则不动)。
     * 生产路径不要走这个 —— 它只是给单测把 sessionId="test" 之类的提前装上。
     */
    public Session createWithId(String id, String title) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        indexLock.lock();
        try {
            Session existing = sessions.get(id);
            if (existing != null) return existing;
            Instant now = Instant.now();
            String resolvedTitle = (title == null || title.isBlank()) ? id : title;
            Session s = new Session(id, resolvedTitle, now, now, 0);
            sessions.put(id, s);
            store.writeIndex(sessions);
            return s;
        } finally {
            indexLock.unlock();
        }
    }

    /** list 全部 session(创建顺序保留)。 */
    public List<Session> list() {
        indexLock.lock();
        try {
            return new ArrayList<>(sessions.values());
        } finally {
            indexLock.unlock();
        }
    }

    /** get 单个 session,不存在抛 {@link NoSuchElementException}。 */
    public Session get(String id) {
        Session s = sessions.get(id);
        if (s == null) {
            throw new NoSuchElementException("session not found: " + id);
        }
        return s;
    }

    public boolean exists(String id) {
        return id != null && sessions.containsKey(id);
    }

    /** 改 title。其它字段不变(createdAt 不动,lastActiveAt 不动)。 */
    public Session updateTitle(String id, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        indexLock.lock();
        try {
            Session existing = get(id);
            Session updated = new Session(
                    existing.id(),
                    title.trim(),
                    existing.createdAt(),
                    existing.lastActiveAt(),
                    existing.messageCount()
            );
            sessions.put(id, updated);
            store.writeIndex(sessions);
            return updated;
        } finally {
            indexLock.unlock();
        }
    }

    /**
     * 删除 session(reserved 不允许)。
     *
     * @throws IllegalArgumentException 如果是 reserved id
     * @throws NoSuchElementException 如果 session 不存在
     */
    public void delete(String id) {
        if (Session.isReserved(id)) {
            throw new IllegalArgumentException("cannot delete reserved session: " + id);
        }
        indexLock.lock();
        try {
            if (!sessions.containsKey(id)) {
                throw new NoSuchElementException("session not found: " + id);
            }
            sessions.remove(id);
            store.writeIndex(sessions);
            store.deleteHistory(id);
            historyCache.remove(id);
            log.info("[Session] deleted {}", id);
        } finally {
            indexLock.unlock();
        }
        // s21 Demo 25:SearchService 钩子放 indexLock 外,不让 SQLite IO 拖住 sessions map 锁
        if (searchService != null) {
            searchService.onDeleteSession(id);
        }
    }

    // ── History API ─────────────────────────────────────────────

    /**
     * 拿到指定 session 的 history list 引用。
     *
     * <p>第一次访问从盘加载到 in-memory cache;后续直接返 cache 引用。
     * 调用方拿到的是**可变** list —— 这正是 AgentLoopHarness 想要的(直接 add,
     * 不用每次 get/set)。
     *
     * <p>如果 sessionId 不在 index 里,会**自动注册一个**(标题用 ID 自己),
     * 这是为了让 cli-default / cron-default / "test" 这种调用方第一次就能拿到 list。
     * 但仍然不会突破 {@link #MAX_SESSIONS} 上限。
     */
    public List<MessageParam> loadHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (!exists(sessionId)) {
            // 优雅注册:让 cli-default / cron-default 等没经过 web create 流程的 session
            // 第一次访问时也能成功。
            createWithId(sessionId, sessionId);
        }
        return historyCache.computeIfAbsent(sessionId, store::readHistory);
    }

    /**
     * 把 history 落盘 + 更新索引(lastActiveAt + messageCount)。
     *
     * <p>每个 turn 结束 / clear 后调用一次。
     */
    public void saveHistory(String sessionId, List<MessageParam> history) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        store.writeHistory(sessionId, history);
        // 更新元数据
        indexLock.lock();
        try {
            Session existing = sessions.get(sessionId);
            if (existing == null) return;
            int count = history != null ? history.size() : 0;
            Session updated = new Session(
                    existing.id(),
                    existing.title(),
                    existing.createdAt(),
                    Instant.now(),
                    count
            );
            sessions.put(sessionId, updated);
            store.writeIndex(sessions);
        } finally {
            indexLock.unlock();
        }
        // s21 Demo 25:SearchService 钩子放 indexLock 外,不让 SQLite IO 拖住 sessions map 锁。
        // 同步双写:LLM 在下一轮 turn 调 session_search 时立即能搜到本轮新内容(语义跟 Hermes 一致)。
        if (searchService != null) {
            searchService.onSaveHistory(sessionId, history);
        }
    }

    /**
     * 清空 history(in-memory + 盘)。reserved session 也允许 clear,
     * 跟 delete 不同 —— clear 只是重置内容。
     */
    public void clearHistory(String sessionId) {
        List<MessageParam> hist = loadHistory(sessionId);
        hist.clear();
        saveHistory(sessionId, hist);
        // s21 Demo 25:saveHistory 已经把空 list 同步到 FTS5(replaceSession 整盘覆盖,
        // DELETE WHERE session_id=? 后没有 INSERT 的话效果跟 clear 一样)。
        // 显式调 onClearHistory 是防御性的 —— 如果未来 saveHistory 钩子改语义,这里仍兜底。
        if (searchService != null) {
            searchService.onClearHistory(sessionId);
        }
    }
}
