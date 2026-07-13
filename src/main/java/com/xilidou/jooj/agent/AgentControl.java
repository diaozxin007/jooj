package com.xilidou.jooj.agent;

/**
 * s22 D-10:agent 循环与外部世界的**双向控制平面**。
 *
 * <h3>为什么整合成一个接口</h3>
 *
 * <p>D-8/D-9 分别为 lead loop / subagent 加了 interrupt 检查点(单向 signal:
 * 外→内 kill flag)。但同类问题还有:
 * <ul>
 *   <li><b>Permission 冒泡</b> —— 工具执行前需要用户 approve,web 场景没通</li>
 *   <li><b>Clarify 问题</b> —— agent 需要用户澄清才能继续(未来)</li>
 *   <li><b>Teammate 停</b> —— 长期协作者也需要能被打断(未来)</li>
 * </ul>
 *
 * <p>本质都是 "agent 循环需要跨线程边界与外部通信"。抽象成一个接口:
 *
 * <ul>
 *   <li><b>signal 部分</b>(非阻塞,外→内)—— {@link #requestInterrupt} / {@link #isInterruptRequested}
 *       / {@link #consumeInterrupt} / {@link #clearInterrupt}。合并原
 *       {@code InterruptRegistry} 的 4 个方法,行为完全一致</li>
 *   <li><b>ask 部分</b>(阻塞,内→外→内)—— 预留 {@code ask} 方法(D-10-B 加),挂起 loop
 *       等用户答复。permission web 冒泡是首个用例</li>
 * </ul>
 *
 * <h3>D-10-A 阶段范围</h3>
 *
 * <p>本 step 只搬 signal 部分,行为跟 D-8/D-9 完全一致(rename)。ask 部分留给 D-10-B。
 *
 * <h3>使用协议(signal 部分,不变)</h3>
 *
 * <ul>
 *   <li>{@link #requestInterrupt(String)} —— REST endpoint 触发,幂等</li>
 *   <li>{@link #consumeInterrupt(String)} —— agentLoop 检查点用,消费并清除 flag</li>
 *   <li>{@link #isInterruptRequested(String)} —— subagent 用(只读,不消费,让 lead 消费一次)</li>
 *   <li>{@link #clearInterrupt(String)} —— session 删除 / 清空时清理</li>
 * </ul>
 *
 * <h3>并发语义</h3>
 *
 * <p>实现类需保证 request/consume 无 lost update。单进程 JVM 假设下(pidfile guard),
 * 不涉及跨进程可见性。REST 线程 request,agentLoop 线程 consume。
 */
public interface AgentControl {

    // ── signal 部分 (D-10-A,合并原 InterruptRegistry) ───────────

    /**
     * 请求打断指定 session 当前正在跑的 turn/task。幂等。
     *
     * @return true = 首次请求;false = 之前已请求过还没被消费
     */
    boolean requestInterrupt(String sessionId);

    /**
     * agentLoop 检查点调用 —— 若被请求打断则**消费并清除** flag,返回 true。
     * 消费后再调返回 false(除非新一次 request)。
     *
     * <p>典型调用者:lead 的 while 顶部 + tool 循环之间。
     */
    boolean consumeInterrupt(String sessionId);

    /**
     * 只读检查是否被请求打断,**不消费**。
     *
     * <p>典型调用者:subagent 内部 —— 让 flag 保留给 lead 消费一次
     * (subagent 抛出 → tool_result → lead 回到 while 顶部真消费 + 走 D-8 事件路径)。
     */
    boolean isInterruptRequested(String sessionId);

    /**
     * 主动清除某 session 的挂起请求 —— session 删除 / 清空时调用,防止 stale flag。
     */
    void clearInterrupt(String sessionId);

    // ── ask 部分 (D-10-B 补) ────────────────────────────────
    // TODO(D-10-B): ask(sid, question, timeout) —— 挂起 loop 等 REST answer
}
