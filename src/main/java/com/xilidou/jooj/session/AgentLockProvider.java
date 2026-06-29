package com.xilidou.jooj.session;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-session lock 池 —— 每个 sessionId 一把独立的 {@link ReentrantLock}。
 *
 * <p>引入 session 抽象后,原来的"全局 agentLock"语义变了:不同 session 应该可以并行,
 * 同 session 内的多个请求才需要互斥(防止两个 user input 撞同一份 history)。
 *
 * <p>cron 仍用 {@link com.xilidou.jooj.session.Session#CRON_DEFAULT_ID} 这把锁,
 * 不会抢用户交互 session 的 lock —— cron-fired turn 的隔离上升到 session 边界本身。
 *
 * <h3>泄漏与生命周期</h3>
 *
 * <p>锁随 session 创建而隐式建立(首次 {@link #lockFor} 命中 computeIfAbsent),
 * 随 session 删除而显式回收({@link #release})。{@link SessionService#MAX_SESSIONS}
 * 也是一个隐含的天花板 —— locks map 永远不会比 sessions map 大。
 */
public class AgentLockProvider {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 拿(或创建)指定 session 的 lock。同一 sessionId 永远是同一把锁。 */
    public ReentrantLock lockFor(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return locks.computeIfAbsent(sessionId, k -> new ReentrantLock());
    }

    /** session 被删除时调,清出 map(idempotent)。 */
    public void release(String sessionId) {
        if (sessionId == null) return;
        locks.remove(sessionId);
    }

    /** 测试用 —— 清空所有 lock。 */
    public void clear() {
        locks.clear();
    }
}
