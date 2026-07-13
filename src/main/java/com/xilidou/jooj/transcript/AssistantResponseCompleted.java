package com.xilidou.jooj.transcript;

import java.time.Instant;
import java.util.UUID;

/**
 * Lead-agent 最终回复完成事件 —— D3 只记最终 assistant 文本,不含 tool 中间态。
 *
 * <p>发布点(2 处):
 * <ul>
 *   <li>{@code AgentLoopHarness.processOneQuery} 出口(user 触发的 turn 完成)</li>
 *   <li>{@code AgentLoopHarness.processCronTriggers} 出口(D8:cron 触发的 turn 也走这条,
 *   前端不区分入口)</li>
 * </ul>
 *
 * <p>{@code content} 应该是 {@code lastAssistantTextSince(history, historyBefore)} 提取出的
 * 纯文本;若 assistant 只有 tool_use 没文本,content 为 blank 时 listener 会跳过 append。
 */
public record AssistantResponseCompleted(
        UUID eventId,
        String sessionId,
        String content,
        Instant timestamp
) implements TranscriptEvent {
}
