package com.xilidou.jooj.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interrupt 触发登记表(s22 D-8)—— 记录哪些 session 被用户请求打断。
 *
 * <h3>为什么独立成 Bean</h3>
 *
 * <p>Interrupt 是**控制信号**,不是 domain 状态,不该塞进 SessionService(session 的
 * 生命周期 vs 单次 turn 的中断,是两个不同关注点)。独立 Bean:
 * <ul>
 *   <li>SRP:只管"有没有被打断"</li>
 *   <li>可扩展:未来可以加 reason(user_click / timeout / preempt)/ timestamp / round_number</li>
 *   <li>可注入到 AgentLoopHarness + ChatController 两个不同上下文</li>
 * </ul>
 *
 * <h3>使用协议</h3>
 *
 * <ul>
 *   <li><b>{@link #request(String)}</b> —— caller(REST endpoint)发起打断请求;
 *       ConcurrentHashMap put,幂等(重复调等价于一次)</li>
 *   <li><b>{@link #consumeIfRequested(String)}</b> —— agentLoop 检查点用;
 *       返回并**清除** flag,防止影响下一次 processOneQuery</li>
 *   <li><b>{@link #isRequested(String)}</b> —— 只读检查,不清除;测试 / 前端状态查询用</li>
 *   <li><b>{@link #clear(String)}</b> —— session 删除 / 清空时清理登记</li>
 * </ul>
 *
 * <h3>并发语义</h3>
 *
 * <p>ConcurrentHashMap 保证 request 和 consume 之间无 lost update。单进程 JVM 假设下
 * (jooj pidfile guard),不涉及跨进程可见性问题。REST 线程 request,agentLoop 线程 consume。
 */
@Component
@Slf4j
public class InterruptRegistry {

    /** Set 语义,值恒 TRUE;用 ConcurrentHashMap 拿并发 add / remove. */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    /**
     * 请求打断指定 session 当前正在跑的 turn。幂等 —— 已在集合里再调无副作用。
     *
     * @param sessionId 目标 session
     * @return true = 首次请求;false = 之前已请求过还没被消费
     */
    public boolean request(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        boolean added = pending.add(sessionId);
        if (added) {
            log.info("[Interrupt] request received sid={}", sessionId);
        } else {
            log.debug("[Interrupt] duplicate request sid={} (already pending)", sessionId);
        }
        return added;
    }

    /**
     * agentLoop 检查点调用 —— 若被请求打断则 **消费并清除** flag,返回 true。
     * 消费后再调返回 false(除非新一次 request)。
     *
     * @param sessionId 当前 loop 的 session
     * @return true = 该打断当前 loop;false = 无未消费的请求
     */
    public boolean consumeIfRequested(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        boolean removed = pending.remove(sessionId);
        if (removed) {
            log.info("[Interrupt] consumed sid={} — will stop current turn", sessionId);
        }
        return removed;
    }

    /**
     * 只读检查是否被请求打断,不消费。用于 REST 状态查询 / 测试断言。
     */
    public boolean isRequested(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return pending.contains(sessionId);
    }

    /**
     * 清除某 session 的挂起请求 —— session 删除 / 清空时调用,防止 stale flag 影响后续。
     */
    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        pending.remove(sessionId);
    }

    /** 测试用:当前挂起请求数。 */
    int pendingCount() {
        return pending.size();
    }
}
