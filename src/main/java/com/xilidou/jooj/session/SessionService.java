package com.xilidou.jooj.session;

import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.search.SearchService;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
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
     */
    private final SearchService searchService;

    /**
     * s21 review:per-session lock 池。delete 时需要 release,防 lock 泄漏。可空兼容老构造。
     * (BUG 1 修复)
     */
    private final AgentLockProvider lockProvider;

    /**
     * 内存索引(单例,启动期从 {@link SessionStore#readIndex()} 加载)。
     *
     * <p>BUG 6 修复:改为 {@code synchronizedMap} 包装的 LinkedHashMap。
     * 所有写路径已加 {@code indexLock},但 {@link #get}/{@link #exists} 等读路径没加锁,
     * 裸 LinkedHashMap 的无锁读跟结构修改(remove/put)并发会出 NPE / 脏数据。
     * {@code synchronizedMap} 让每次 get/containsKey 也原子化,消除读路径的数据竞争。
     * 写路径的 indexLock 仍保留:保证多步骤 (read-modify-write) 的原子性,
     * 单步 synchronizedMap 锁只能保证单次调用,跨调用还是需要 indexLock。
     */
    private final Map<String, Session> sessions =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /** 写 index 时的互斥锁。CRUD 都串行,保证多步 read-modify-write 原子性。 */
    private final ReentrantLock indexLock = new ReentrantLock();

    /** in-memory history 缓存:同 session 多次访问不重复读盘。 */
    private final Map<String, List<MessageParam>> historyCache = new ConcurrentHashMap<>();

    public SessionService(SessionStore store) {
        this(store, null, null);
    }

    /** s21 Demo 25 2 参:接 SearchService,不接 lockProvider(向后兼容)。 */
    public SessionService(SessionStore store, SearchService searchService) {
        this(store, searchService, null);
    }

    /**
     * 完整 3 参构造器 —— 生产 Spring 装配走这条。
     *
     * @param lockProvider  nullable;非 null 时 delete 调 {@link AgentLockProvider#release}
     */
    public SessionService(SessionStore store, SearchService searchService,
                          AgentLockProvider lockProvider) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        this.store = store;
        this.searchService = searchService;
        this.lockProvider = lockProvider;
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
     * 按指定 ID 创建 session(若已存在则幂等返回已有)。
     *
     * <p>典型调用方:
     * <ul>
     *   <li>{@link #ensureBootstrap} —— 三个 reserved session</li>
     *   <li>{@link #loadHistory} —— 第一次访问某 session 时自动注册</li>
     *   <li>{@link com.xilidou.jooj.channel.InboundDispatcher} —— IM peer 第一条消息时建 session</li>
     * </ul>
     *
     * <p>BUG 7 修复:加 {@link #MAX_SESSIONS} 检查。
     * 之前 {@code create} 有上限,{@code createWithId} 绕过,导致 channel 场景每来一个新 peer
     * 就能绕过限制建 session。现在 createWithId 也强制检查,但 reserved session(bootstrap 阶段)
     * 是例外 —— bootstrap 发生在 container 刚起时,通常 map 是空的,不会超限;就算超限,
     * reserved session 不存在会导致更严重的功能失效,所以 reserved 豁免检查。
     */
    public Session createWithId(String id, String title) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        indexLock.lock();
        try {
            Session existing = sessions.get(id);
            if (existing != null) return existing;
            // BUG 7:非 reserved session 也要检查上限
            if (!Session.isReserved(id) && sessions.size() >= MAX_SESSIONS) {
                throw new IllegalStateException(
                        "Reached max sessions (" + MAX_SESSIONS + "). Please delete some.");
            }
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
        // BUG 6 修复:sessions 已改 synchronizedMap,单次 get 安全;不需要 indexLock。
        // indexLock 只用于保护多步 read-modify-write 操作。
        Session s = sessions.get(id);
        if (s == null) {
            throw new NoSuchElementException("session not found: " + id);
        }
        return s;
    }

    /** 检查 session 是否存在。 */
    public boolean exists(String id) {
        // BUG 6 修复:同上,synchronizedMap 保证单次读安全。
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
            // BUG 5 修复:historyCache 在 indexLock 内清,避免 delete 与 loadHistory 竞态。
            // 若 loadHistory 在 indexLock 外 computeIfAbsent,delete 之后 cache 仍残留;
            // 统一在 indexLock 内清,delete 完成后 cache 必然空,下次 loadHistory 走 createWithId。
            historyCache.remove(id);
            log.info("[Session] deleted {}", id);
        } finally {
            indexLock.unlock();
        }
        // BUG 1 修复:释放 per-session lock,防 AgentLockProvider.locks map 永远只增不减。
        if (lockProvider != null) {
            lockProvider.release(id);
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
        return historyCache.computeIfAbsent(sessionId, sid -> {
            // s21 Demo 25 副作用:磁盘上的 session JSON 可能含孤儿 tool_use / tool_result
            // (历史 SnipCompactor 切坏 / 进程崩溃半截 / 倒入的不完整 history),
            // Anthropic 收到立刻 400。读盘后做一次 self-consistent scrub 兜底,
            // 把孤儿块过滤掉再塞 cache,后续 saveHistory 自然把净化结果写回 JSON。
            List<MessageParam> raw = store.readHistory(sid);
            // scrub 出来要是个 mutable ArrayList,因为 AgentLoopHarness 直接 add 到这个引用
            List<MessageParam> scrubbed = HistoryScrubber.scrub(raw);
            // scrub 可能返回原引用(无变化时),也可能返回新 ArrayList。
            // 都包成 ArrayList 保证可变。
            return scrubbed instanceof ArrayList<MessageParam> al ? al : new ArrayList<>(scrubbed);
        });
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
            // BUG 2 修复:existing==null 时不再静默 return。
            // 原来的行为:JSON 已写盘,但 index 没更新 → 状态永久不一致。
            // 现在:log warn 并自动注册(跟 loadHistory 的"优雅注册"同语义),
            // 然后继续更新 index。这样 JSON 写了,index 也跟上。
            if (existing == null) {
                log.warn("[Session] saveHistory called for unknown session {}, auto-registering",
                        sessionId);
                Instant now = Instant.now();
                existing = new Session(sessionId, sessionId, now, now, 0);
                sessions.put(sessionId, existing);
            }
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
        // s22 P3-b:SearchService 索引改为事件驱动,不再在 saveHistory 尾部整盘覆盖。
        // 事件流(UserMessageReceived / ScheduledPromptFired / AssistantResponseCompleted)
        // 已经在 AgentLoopHarness 发布,SearchService 作为 EventListener 直接 append。
        // 好处:搜索只索引干净原文,不再命中 <memories>...</memories> 之类污染。
    }

    /**
     * 清空 history(in-memory + 盘)。reserved session 也允许 clear,
     * 跟 delete 不同 —— clear 只是重置内容。
     *
     * <p>s22 P3-b:saveHistory 不再自动写 FTS 后,clearHistory 必须显式调
     * {@code onClearHistory} 才能清 FTS。回补之前 BUG 3 修复删掉的调用 ——
     * 那次删除的前提是 saveHistory 会 replaceSession(sid, []),现在这个前提已不成立。
     */
    public void clearHistory(String sessionId) {
        List<MessageParam> hist = loadHistory(sessionId);
        hist.clear();
        saveHistory(sessionId, hist);
        if (searchService != null) {
            searchService.onClearHistory(sessionId);
        }
    }
}
