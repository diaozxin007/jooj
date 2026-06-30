package com.xilidou.jooj.search;

import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.session.Session;
import com.xilidou.jooj.session.SessionService;
import com.xilidou.jooj.session.SessionStore;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

/**
 * SearchService —— SearchStore 业务包装,SessionService 钩子 + Tool 入口。
 *
 * <p>跟 {@code MemoryService} / {@code TaskService} 同模式:facade 层,内部串
 * {@link SearchStore} 单一 IO 层 + 业务钩子。
 *
 * <h3>职责</h3>
 *
 * <ul>
 *   <li>{@link #onSaveHistory} — SessionService.saveHistory 末尾调,失败 warn 不重抛</li>
 *   <li>{@link #onDeleteSession} — SessionService.delete 调</li>
 *   <li>{@link #onClearHistory} — SessionService.clearHistory 调</li>
 *   <li>{@link #search} — Tool / API 入口,limit clamp 到 maxLimit</li>
 *   <li>{@link #rebuildAll} — 手动 API,扫所有 session JSON 反向 import 进 FTS5</li>
 * </ul>
 *
 * <p><b>关键设计</b>:钩子里所有 SQLite 异常都吞掉(warn level),不重抛 ——
 * SearchStore 是 JSON 衍生 view,失败应该让 SessionService 的 JSON 主流程继续。
 */
@Slf4j
public class SearchService {

    private final SearchStore store;
    private final SearchConfig config;

    public SearchService(SearchStore store, SearchConfig config) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.store = store;
        this.config = config;
    }

    public SearchConfig getConfig() {
        return config;
    }

    // ── SessionService 钩子 ────────────────────────────────────

    /** SessionService.saveHistory 末尾调。失败仅 warn,不抛。 */
    public void onSaveHistory(String sessionId, List<MessageParam> history) {
        try {
            store.replaceSession(sessionId, history, Instant.now());
        } catch (Throwable t) {
            log.warn("[Search] onSaveHistory({}) failed (JSON path unaffected): {}",
                    sessionId, t.toString());
        }
    }

    /** SessionService.delete 调。失败仅 warn,不抛。 */
    public void onDeleteSession(String sessionId) {
        try {
            store.deleteSession(sessionId);
        } catch (Throwable t) {
            log.warn("[Search] onDeleteSession({}) failed: {}", sessionId, t.toString());
        }
    }

    /** SessionService.clearHistory 调。失败仅 warn,不抛。 */
    public void onClearHistory(String sessionId) {
        try {
            store.clearSession(sessionId);
        } catch (Throwable t) {
            log.warn("[Search] onClearHistory({}) failed: {}", sessionId, t.toString());
        }
    }

    // ── Tool 入口 ──────────────────────────────────────────────

    /** Tool 入口:search FTS5 + clamp limit。query 为空 → 空 list。 */
    public List<SearchHit> search(SearchQuery q) {
        if (q == null) return List.of();
        // clamp limit:LLM 传超大值会让 result 撑爆 LLM 输出,clamp 到 maxLimit
        int limit = Math.min(Math.max(1, q.limit()), config.maxLimit());
        SearchQuery clamped = new SearchQuery(
                q.query(), q.sessionId(), q.role(), q.kind(), limit);
        return store.search(clamped);
    }

    public int defaultLimit() {
        return config.defaultLimit();
    }

    public int maxLimit() {
        return config.maxLimit();
    }

    // ── Maintenance API ────────────────────────────────────────

    /**
     * 手动 rebuildAll —— 扫所有 session JSON 反向 import 进 FTS5。
     * 启动期不自动调(jooj REPL 启动期不该卡),用户/管理员显式触发。
     *
     * @return 重建涉及的 session 个数
     */
    public int rebuildAll(SessionService sessionService, SessionStore sessionStore) {
        if (sessionService == null || sessionStore == null) {
            log.warn("[Search] rebuildAll: sessionService or sessionStore is null");
            return 0;
        }
        List<Session> sessions = sessionService.list();
        List<String> ids = sessions.stream().map(Session::id).toList();
        log.info("[Search] rebuildAll start: {} sessions", ids.size());
        store.rebuild(sessionStore, ids);
        log.info("[Search] rebuildAll done: {} sessions imported", ids.size());
        return ids.size();
    }

    /** strict mode 启动期一致性检查用 —— 暴露 store countSession。 */
    public int countSession(String sessionId) {
        return store.countSession(sessionId);
    }

    /** 调试 / 监控用。 */
    public int countAll() {
        return store.countAll();
    }
}
