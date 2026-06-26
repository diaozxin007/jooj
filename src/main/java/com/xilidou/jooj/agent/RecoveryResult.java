package com.xilidou.jooj.agent;

import com.xilidou.jooj.http.dto.CreateMessageResponse;

/**
 * {@link RecoveryCoordinator#call} 的返回类型。sealed interface,4 种结局让
 * 调用方(AgentLoopHarness)用模式匹配处理:
 *
 * <ul>
 *   <li>{@link Done} —— 正常拿到 LLM 响应。stop_reason 可能是 end_turn / tool_use,
 *       后续逻辑由 agentLoop 决定</li>
 *   <li>{@link EscalateAndRetry} —— Path 1 第一次 max_tokens 升级,**不要 append** 截断的输出,
 *       直接重建 request 用新的 currentMaxTokens 重试</li>
 *   <li>{@link AppendContinuation} —— Path 1 已升级到 64K 仍截断,append 截断输出 +
 *       一条 continuation user message,然后重试</li>
 *   <li>{@link Fatal} —— 不可恢复,在 agentLoop 里 append 错误说明给用户后退出 loop</li>
 * </ul>
 *
 * <p>抽出 sealed interface 而非用 boolean / enum 的原因:
 * AppendContinuation 必须携带要 append 的 response 和 prompt,Done 必须携带响应。
 * sealed 让编译期保证 agentLoop 的 switch 覆盖了所有分支。
 */
public sealed interface RecoveryResult {

    /** 正常完成,LLM 给出有效响应。 */
    record Done(CreateMessageResponse response) implements RecoveryResult {}

    /**
     * Path 1 第一次升级:不 append 截断的 response,直接用新 max_tokens 重新调 LLM。
     *
     * <p>原因:升级前的 response 是被截断的部分文本,append 后会让模型以为已经输出过 ——
     * 重新调用时会从那里继续,而不是重新生成完整答案。Python 原版严格遵循这个语义。
     */
    record EscalateAndRetry() implements RecoveryResult {}

    /**
     * Path 1 第二阶段:已升级到 64K 仍截断。append 截断的 response + 一条
     * continuation user message,让模型从中断处续写。
     *
     * @param response     被截断的 LLM 响应,append 进 history
     * @param continuation 注入的 user prompt(让 LLM 从断点处续写)
     */
    record AppendContinuation(CreateMessageResponse response, String continuation)
            implements RecoveryResult {}

    /**
     * 不可恢复错误。agentLoop 应该在 history 末尾追加一条解释,然后从 loop 退出。
     *
     * @param reason 给用户看的简短描述(出现在最终 assistant text 里)
     */
    record Fatal(String reason) implements RecoveryResult {}
}
