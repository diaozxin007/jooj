package com.xilidou.jooj.todo;

import com.xilidou.jooj.transcript.SessionDeleted;
import com.xilidou.jooj.transcript.SessionHistoryCleared;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 待办列表存储 —— per-session 隔离(s20 Demo 12 重构)。
 *
 * <h3>历史</h3>
 *
 * <p>对应 Python s05 的全局变量 {@code CURRENT_TODOS = []}。最初教学版 jooj 直接搬过来,
 * 用一个 {@code List<TodoItem>} 全局共享 —— 当时 jooj 是单 session(CLI REPL 一进一出),
 * 没问题。
 *
 * <p>s17 引入多 session,Demo 11 加 IM channel 后,**alice 跟你聊微信、bob 同时在 web
 * 写代码 → 两边的 todo 列表会互相覆盖**。Demo 12 修复:内部 {@code Map<sessionId, List>}
 * 分区。
 *
 * <h3>API 兼容</h3>
 *
 * <p>保留**老 5 个无参 API**({@link #replace} / {@link #snapshot} / {@link #size} ...),
 * 内部委托到一个 {@link #DEFAULT_SESSION} 分区。这样:
 * <ul>
 *   <li>老调用方(测试 / 没有 sessionId 的兼容路径)继续可用</li>
 *   <li>新调用方走 sessionId 重载,获得隔离</li>
 * </ul>
 *
 * <h3>同步</h3>
 *
 * <p>{@code synchronized} 整个 method,同 session 内仍是 race-free。
 * 跨 session 写不同 key 也是安全的(Map 整体加锁,串行化 Map 操作 + List 操作)。
 *
 * <p>**为什么不用 ConcurrentHashMap**?语义是"整个 list 替换",不是"按 id 单条更新",
 * synchronized + HashMap + ArrayList 比 Concurrent 表达更准确。
 */
@Component
@Slf4j
public class TodoStore {

    /** 老调用方(无 sessionId)的 fallback 分区。也是测试默认走的桶。 */
    public static final String DEFAULT_SESSION = "_default";

    /** sessionId → todo list。一个 session 一份独立 list。 */
    private final Map<String, List<TodoItem>> bySession = new HashMap<>();

    // ── 新 API:带 sessionId,Demo 12 起这是首选路径 ────────────────────

    /**
     * 整体替换指定 session 的 todo list。
     *
     * @param sessionId 路由分区 key;null/blank → 用 {@link #DEFAULT_SESSION}
     * @param newTodos  新列表,null 视作空列表
     */
    public synchronized void replace(String sessionId, List<TodoItem> newTodos) {
        String sid = normalize(sessionId);
        List<TodoItem> next = (newTodos == null) ? new ArrayList<>() : new ArrayList<>(newTodos);
        bySession.put(sid, next);
        log.debug("[TodoStore] session={} replaced with {} items", sid, next.size());
    }

    public synchronized List<TodoItem> snapshot(String sessionId) {
        List<TodoItem> list = bySession.get(normalize(sessionId));
        if (list == null) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    public synchronized int size(String sessionId) {
        List<TodoItem> list = bySession.get(normalize(sessionId));
        return list == null ? 0 : list.size();
    }

    public synchronized boolean isEmpty(String sessionId) {
        return size(sessionId) == 0;
    }

    public synchronized long countByStatus(String sessionId, TodoStatus status) {
        List<TodoItem> list = bySession.get(normalize(sessionId));
        if (list == null) return 0;
        return list.stream().filter(t -> t.getStatus() == status).count();
    }

    public synchronized void clear(String sessionId) {
        bySession.remove(normalize(sessionId));
    }

    /**
     * s22 架构审查(2026-07-13):session 历史清空事件 → 清 todo 分区。
     * 取代旧的 AgentLoopHarness.onNewSession(sid -> todoStore.clear(sid)) 中转,
     * 让 todo 生命周期钩子直接跟 session 生命周期事件对齐。
     */
    @EventListener
    public void onSessionHistoryCleared(SessionHistoryCleared e) {
        clear(e.sessionId());
    }

    /**
     * s22 架构审查:session 被删也清对应 todo 分区,避免 bySession map 无限增长。
     */
    @EventListener
    public void onSessionDeleted(SessionDeleted e) {
        clear(e.sessionId());
    }

    // ── 老 API:无 sessionId,委托给 DEFAULT_SESSION 分区 ─────────────
    // 保留为了让现有测试 / 兼容路径不被破坏。新代码请用上面的重载。

    public synchronized void replace(List<TodoItem> newTodos) {
        replace(DEFAULT_SESSION, newTodos);
    }

    public synchronized List<TodoItem> snapshot() {
        return snapshot(DEFAULT_SESSION);
    }

    public synchronized int size() {
        return size(DEFAULT_SESSION);
    }

    public synchronized boolean isEmpty() {
        return isEmpty(DEFAULT_SESSION);
    }

    public synchronized long countByStatus(TodoStatus status) {
        return countByStatus(DEFAULT_SESSION, status);
    }

    public synchronized void clear() {
        clear(DEFAULT_SESSION);
    }

    private static String normalize(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? DEFAULT_SESSION : sessionId;
    }
}
