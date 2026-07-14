package com.xilidou.jooj.agent.control;

import java.time.Instant;
import java.util.UUID;

/**
 * s22 D-10-B:agent 内部循环向外部(用户)提出的"需要答复"的问题。
 *
 * <h3>本质</h3>
 *
 * <p>三件事其实是同一件事:agent 循环需要跨线程边界与外部**双向**通信。
 * D-8/D-9/D-10-A 已经建了 signal(外→内,非阻塞,如 interrupt),D-10-B 建 ask(内→外→内,阻塞)。
 *
 * <p>典型用例:
 * <ul>
 *   <li>{@link PermissionQuestion} —— 工具执行前需要用户 approve(rm -rf、writeFile 等)</li>
 *   <li>预留:ClarifyQuestion / ChooseOptionQuestion(未来 agent 主动问用户)</li>
 * </ul>
 *
 * <h3>协议</h3>
 *
 * <p>agent 线程 {@code AgentControl.ask(sid, question, timeout)} 挂起,REST 层通过
 * {@code GET /pending} 看到 question,用户答复通过 {@code POST /answer} 唤醒 agent。
 *
 * <p>{@code askId} 由 {@link #newAskId()} 生成,REST 用它匹配 answer 和 pending question。
 *
 * <p><b>只读性</b>:PendingQuestion 记录**从创建到被 answer/timeout/cancel** 的元数据,
 * 一旦提交就不该变;askedAt 是时间戳,不含"当前状态"字段。状态由 AgentControl 内部维护
 * (CompletableFuture 未完成 = pending,已完成 = 已 answer/timeout/cancel)。
 */
public sealed interface PendingQuestion
        permits PermissionQuestion, ClarifyQuestion {

    /** 唯一 ID,前端 /pending /answer 匹配用。 */
    String askId();

    /** 挂起时间,前端可以显示"等待多久了"。 */
    Instant askedAt();

    /**
     * 问题类型的短标签,给前端选渲染样式用。
     * 目前:{@code "permission"}。将来:{@code "clarify"}、{@code "choose"} 等。
     */
    String type();

    /** 生成新的 askId,内部用 UUID。 */
    static String newAskId() {
        return UUID.randomUUID().toString();
    }
}
