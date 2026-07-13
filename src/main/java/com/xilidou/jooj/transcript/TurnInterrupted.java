package com.xilidou.jooj.transcript;

import java.time.Instant;
import java.util.UUID;

/**
 * s22 D-8:一轮 turn 被用户主动打断的事件 —— 用户通过 UI/REST 请求
 * {@code /api/chat/{sid}/interrupt},agentLoop 在检查点抛
 * {@link com.xilidou.jooj.agent.AgentInterruptedException},由 processOneQuery
 * 兜底捕获后 publish 本事件。
 *
 * <p>跟 {@link AssistantResponseCompleted} 的区分:
 * <ul>
 *   <li>{@code AssistantResponseCompleted} —— turn 正常结束,有 final assistant reply</li>
 *   <li>{@code TurnInterrupted} —— turn 被用户强制打断,可能 messages 里已有 partial assistant,
 *       但 caller 视角是"这次没得到完整答复"。前端渲染系统气泡"[已中断]"</li>
 * </ul>
 *
 * <p>{@code partialContent} 是打断前 lead-agent 已 append 的 assistant 文本(可能为空)。
 * 前端可选择显示"partial answer + 中断标记"或者只显示"中断"标记。
 */
public record TurnInterrupted(
        UUID eventId,
        String sessionId,
        String partialContent,
        Instant timestamp
) implements TranscriptEvent {
}
