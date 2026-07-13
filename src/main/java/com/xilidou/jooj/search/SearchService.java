package com.xilidou.jooj.search;

import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.session.Session;
import com.xilidou.jooj.session.SessionService;
import com.xilidou.jooj.session.SessionStore;
import com.xilidou.jooj.transcript.AssistantResponseCompleted;
import com.xilidou.jooj.transcript.ScheduledPromptFired;
import com.xilidou.jooj.transcript.SessionDeleted;
import com.xilidou.jooj.transcript.SessionHistoryCleared;
import com.xilidou.jooj.transcript.UserMessageReceived;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SearchService —— SearchStore 业务包装,事件驱动索引 + Tool 入口。
 *
 * <p>跟 {@code MemoryService} / {@code TaskService} 同模式:facade 层,内部串
 * {@link SearchStore} 单一 IO 层 + 业务钩子。
 *
 * <h3>职责(s22 P3-b 之后)</h3>
 *
 * <ul>
 *   <li>{@link #onUserMessage} / {@link #onScheduledPrompt} / {@link #onAssistantResponse}
 *       —— {@link EventListener},事件到就 incremental append 到 FTS,只索引干净原文(D2 / D4.4)</li>
 *   <li>{@link #onSessionDeleted} —— 事件驱动删除索引</li>
 *   <li>{@link #search} — Tool / API 入口,limit clamp 到 maxLimit</li>
 *   <li>{@link #rebuildAll} — 手动 API,扫所有 session JSON 反向 import 进 FTS5
 *       (legacy 数据一次性重建;日常事件走 event listener,不走这里)</li>
 * </ul>
 *
 * <h3>历史</h3>
 *
 * <p>s22 P3-b 前,SearchService 通过 {@code onSaveHistory(sid, history)} 钩子被
 * {@link SessionService#saveHistory} 调用,直接把**带 memory prefix 污染**的
 * history 整盘覆盖式索引进 FTS。搜索会命中 {@code <memories>...</memories>} 里的内容,
 * 用户搜自己敲过的原文反而搜不到。
 *
 * <p>s22 P3-b 改成事件驱动:{@code TranscriptEvent} 里带的是干净原文,
 * SearchService 监听事件直接 {@link SearchStore#appendOne} 增量索引,污染问题
 * 从根源杜绝。SessionService 那边的 onSaveHistory 钩子调用已删除。
 *
 * <h3>D11 幂等</h3>
 *
 * <p>持有独立 LRU {@code seenEvents},跟 TranscriptService 各自维护,互不干扰。
 * 同一 eventId 只索引一次;写盘失败回退 LRU 允许重试。
 *
 * <p><b>关键设计</b>:listener 里所有 SQLite 异常都吞掉(warn level),不重抛 ——
 * SearchStore 是衍生 view,失败不应该冒到 loop 主路径。
 */
@Slf4j
public class SearchService {

    /** D11 LRU cap —— 跟 TranscriptService 保持一致(4096)。 */
    static final int DEDUP_CAP = 4096;

    private final SearchStore store;
    private final SearchConfig config;

    /**
     * D11 幂等 LRU。跟 TranscriptService 各自持有独立 set,互不干扰。
     */
    private final Set<UUID> seenEvents = Collections.newSetFromMap(
            new LinkedHashMap<UUID, Boolean>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > DEDUP_CAP;
                }
            });

    public SearchService(SearchStore store, SearchConfig config) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.store = store;
        this.config = config;
    }

    public SearchConfig getConfig() {
        return config;
    }

    // ── 事件 listener(s22 P3-b)─────────────────────────────────

    @EventListener
    public void onUserMessage(UserMessageReceived e) {
        if (!acquireEvent(e.eventId())) return;
        try {
            store.appendOne(e.sessionId(), "user", e.content(), e.timestamp());
        } catch (Throwable t) {
            releaseOnFailure(e.eventId());
            log.warn("[Search] onUserMessage({}) failed: {}", e.sessionId(), t.toString());
        }
    }

    /** D7:scheduled event 也进 FTS,role="scheduled" 便于按类型筛。 */
    @EventListener
    public void onScheduledPrompt(ScheduledPromptFired e) {
        if (!acquireEvent(e.eventId())) return;
        try {
            store.appendOne(e.sessionId(), "scheduled", e.prompt(), e.timestamp());
        } catch (Throwable t) {
            releaseOnFailure(e.eventId());
            log.warn("[Search] onScheduledPrompt({}, job={}) failed: {}",
                    e.sessionId(), e.jobId(), t.toString());
        }
    }

    @EventListener
    public void onAssistantResponse(AssistantResponseCompleted e) {
        if (e.content() == null || e.content().isBlank()) return;
        if (!acquireEvent(e.eventId())) return;
        try {
            store.appendOne(e.sessionId(), "assistant", e.content(), e.timestamp());
        } catch (Throwable t) {
            releaseOnFailure(e.eventId());
            log.warn("[Search] onAssistantResponse({}) failed: {}", e.sessionId(), t.toString());
        }
    }

    /** D6:session 删除,索引整体删除(未来若需软标记 deleted_at 再改)。 */
    @EventListener
    public void onSessionDeleted(SessionDeleted e) {
        if (!acquireEvent(e.eventId())) return;
        try {
            store.deleteSession(e.sessionId());
        } catch (Throwable t) {
            releaseOnFailure(e.eventId());
            log.warn("[Search] onSessionDeleted({}) failed: {}", e.sessionId(), t.toString());
        }
    }

    /**
     * SessionHistoryCleared 事件 —— 跟 delete 相同处理:清索引。
     * 从用户"看不到之前对话"的视角出发,历史索引不该再命中。
     */
    @EventListener
    public void onSessionHistoryCleared(SessionHistoryCleared e) {
        if (!acquireEvent(e.eventId())) return;
        try {
            store.clearSession(e.sessionId());
        } catch (Throwable t) {
            releaseOnFailure(e.eventId());
            log.warn("[Search] onSessionHistoryCleared({}) failed: {}",
                    e.sessionId(), t.toString());
        }
    }

    // ── SessionService 钩子(保留兜底) ────────────────────────

    /**
     * @deprecated s22 P3-b 后,日常索引走 {@link #onUserMessage} 等 event listener。
     *     本方法保留只服务于 legacy 路径(如 {@link #rebuildAll})和向后兼容;
     *     SessionService.saveHistory 不再调本方法。
     */
    @Deprecated
    public void onSaveHistory(String sessionId, List<MessageParam> history) {
        try {
            store.replaceSession(sessionId, history, Instant.now());
        } catch (Throwable t) {
            log.warn("[Search] onSaveHistory({}) failed (JSON path unaffected): {}",
                    sessionId, t.toString());
        }
    }

    /**
     * SessionService.delete 调 —— s22 P3-b:保留作兜底,event listener
     * {@link #onSessionDeleted} 才是主路径。SessionService 目前仍会调本方法保证
     * 即便 event 派发失败索引也会被清。失败仅 warn,不抛。
     */
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

    // ── D11 幂等 gate ─────────────────────────────────────────

    private boolean acquireEvent(UUID eventId) {
        if (eventId == null) return true;
        synchronized (seenEvents) {
            return seenEvents.add(eventId);
        }
    }

    private void releaseOnFailure(UUID eventId) {
        if (eventId == null) return;
        synchronized (seenEvents) {
            seenEvents.remove(eventId);
        }
    }

    /** 单测用:检查 LRU 大小。 */
    int seenEventsSize() {
        synchronized (seenEvents) {
            return seenEvents.size();
        }
    }
}
