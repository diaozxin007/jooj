package com.xilidou.jooj.agent;

/**
 * Recovery 三条恢复路径都尝试过后仍无法拿到 LLM 响应时抛出。
 *
 * <h3>为什么是 checked exception</h3>
 *
 * <p>Loop 必须显式处理"最终失败"分支 —— 在 messages 里 append 一条 Fatal-shape
 * {@code [Error]} assistant 消息,然后结束本轮 turn。用 checked exception 强制
 * caller 有 try/catch,不会漏。
 *
 * <h3>Fatal 场景(内部由 RecoveryCoordinator 触发)</h3>
 *
 * <ul>
 *   <li>Path 1 max_tokens:已升级 + 已用完 maxRecoveryRetries 次 continuation</li>
 *   <li>Path 2 prompt_too_long:reactive compact 失败,或第二次触发(已 attempt 过)</li>
 *   <li>Path 3 non-retryable 4xx:比如 400 invalid_request,直接放弃</li>
 *   <li>Path 3 retry 耗尽:429/529 连续 maxRetries 次仍失败</li>
 * </ul>
 *
 * <h3>Loop 侧处理模板</h3>
 *
 * <pre>{@code
 * CreateMessageResponse response;
 * try {
 *     response = recoveryCoordinator.call(reqBuilder, messages, state);
 * } catch (FatalRecoveryException e) {
 *     messages.add(MessageParam.assistant(List.of(
 *             new TextBlock("[Error] " + e.getReason()))));
 *     return;  // 结束本轮 turn
 * }
 * // response 保证 non-null 且非 max_tokens 截断
 * }</pre>
 *
 * <p>{@code "[Error] "} 前缀是 loop 侧的约定 —— {@code ChatHistoryMapper} 认这个前缀
 * 会把消息翻译成 {@code SYSTEM_NOTICE(ERROR)},前端渲染成错误气泡。
 */
public class FatalRecoveryException extends Exception {

    private final String reason;

    public FatalRecoveryException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /** 短原因(不带堆栈),用作 assistant 消息里的错误说明。 */
    public String getReason() {
        return reason;
    }
}
