package com.xilidou.jooj.agent;

/**
 * s22 D-8:agentLoop 主动抛出的中断信号 —— 用户通过 REST/UI 请求打断当前 turn 时,
 * {@link AgentControl#consumeInterrupt(String)} 会在 loop 检查点返回 true,
 * 检查点抛此异常向 processOneQuery 冒泡,由 processOneQuery 处理后续:
 *
 * <ul>
 *   <li>append {@code [Interrupted by user]} 到 messages(LLM 下一轮能看到)</li>
 *   <li>publish {@code AssistantResponseCompleted} 或 {@code TurnInterrupted} 事件(前端渲染)</li>
 *   <li>正常持久化 messages(不回滚)</li>
 * </ul>
 *
 * <p>选 unchecked:检查点分散在 while 顶部 + tool 循环中间,要求每处都写 {@code throws} 太啰嗦;
 * FatalRecoveryException 是 caller 必须响应的 signal(所以 checked),Interrupt 走"抛到边界统一处理"
 * 模式,更适合 runtime。
 */
public class AgentInterruptedException extends RuntimeException {

    /** 触发时的 session,用于日志和事件发布。 */
    private final String sessionId;

    public AgentInterruptedException(String sessionId) {
        super("Agent loop interrupted by user request for session=" + sessionId);
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
