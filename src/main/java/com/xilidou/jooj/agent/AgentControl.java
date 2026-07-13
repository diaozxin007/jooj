package com.xilidou.jooj.agent;

import com.xilidou.jooj.agent.control.Answer;
import com.xilidou.jooj.agent.control.AskTimeoutException;
import com.xilidou.jooj.agent.control.PendingQuestion;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

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

    // ── ask 部分 (D-10-B) ──────────────────────────────────────

    /**
     * 挂起当前 agent 线程,把 question 推到 sessionId 的 pending 队列,阻塞等答复。
     *
     * <p><b>Web 流程</b>:
     * <ol>
     *   <li>agent 线程调 ask(),内部 CompletableFuture 挂起</li>
     *   <li>REST {@code GET /pending} 看到 question,前端弹框</li>
     *   <li>用户点"允许"→ {@code POST /answer} → {@link #answer(String, String, Answer)}
     *       → CompletableFuture.complete → agent 线程恢复,拿到 Answer</li>
     *   <li>如果 timeout 到,抛 {@link AskTimeoutException}</li>
     *   <li>如果期间 lead 被 interrupt 了,ask 被 cancel,抛
     *       {@link AgentInterruptedException}(D-10-B step 4 打通)</li>
     * </ol>
     *
     * <p><b>Console 流程</b>:实现方(如 CLI ConsoleAgentControl,D-10-C 可选建)
     * 可以走 stdin 阻塞读,不进 pending 队列。契约相同。
     *
     * @param sessionId 会话 ID,前端 /pending?sid=xxx 按 sid 查询
     * @param question  待问问题
     * @param timeout   阻塞超时,超时抛 AskTimeoutException;调用方 catch 后自行 DENY 兜底
     * @return 用户答复
     * @throws AskTimeoutException          超时未答
     * @throws AgentInterruptedException    挂起期间被 interrupt(D-10-B step 4)
     * @throws InterruptedException         线程被物理 interrupt(不是用户 signal,是 JVM 层)
     */
    Answer ask(String sessionId, PendingQuestion question, Duration timeout)
            throws AskTimeoutException, AgentInterruptedException, InterruptedException;

    /**
     * REST {@code GET /pending?sessionId=xxx} 的后端逻辑:
     * 返当前 session 挂起的所有 pending question(可能 0 或多个,常见 1 个)。
     */
    List<PendingQuestion> listPending(String sessionId);

    /**
     * REST {@code POST /answer} 的后端逻辑:通过 askId 找到挂起的 CompletableFuture 并 complete。
     *
     * @return true = 找到了并唤醒;false = askId 不存在(可能已 timeout/cancel/answered)
     */
    boolean answer(String sessionId, String askId, Answer answer);

    /**
     * 只读查询单个 pending(测试 / 状态排查用)。
     */
    Optional<PendingQuestion> findPending(String sessionId, String askId);

    /**
     * 取消 session 下所有 pending question 的等待:用户 interrupt 时调用,
     * 让挂起的 agent 线程抛 {@link AgentInterruptedException}(D-10-B step 4)。
     *
     * @return 被 cancel 的 pending 数量
     */
    int cancelPending(String sessionId);
}
