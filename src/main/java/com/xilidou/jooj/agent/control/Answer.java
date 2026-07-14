package com.xilidou.jooj.agent.control;

/**
 * s22 D-10-B:用户对 {@link PendingQuestion} 的答复。
 *
 * <h3>Sealed 3 种</h3>
 *
 * <ul>
 *   <li>{@link AllowAnswer} —— 用户点"允许"</li>
 *   <li>{@link DenyAnswer} —— 用户点"拒绝",带原因</li>
 *   <li>{@link TextAnswer} —— 未来 clarify 用,自由文本</li>
 * </ul>
 *
 * <p>{@code AgentControl.ask(sid, question, timeout)} 阻塞返回本类型。
 * 调用方(如 PermissionHook)据此决定 ALLOW/DENY tool。
 *
 * <p><b>为什么 sealed</b>:强 typed 答复防止 permission ASK 收到 "hello world" 文本这类
 * 类型不匹配的场景;编译期就能约束。
 */
public sealed interface Answer permits AllowAnswer, DenyAnswer, TextAnswer, ChoiceAnswer {

    /** 便利:是不是"允许"型答复。 */
    default boolean isAllow() {
        return this instanceof AllowAnswer;
    }

    /** 便利:是不是"拒绝"型答复。 */
    default boolean isDeny() {
        return this instanceof DenyAnswer;
    }
}
