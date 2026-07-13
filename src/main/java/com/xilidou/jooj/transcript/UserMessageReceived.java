package com.xilidou.jooj.transcript;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户消息事件 —— 用户在 Web / CLI / Channel 输入的原文。
 *
 * <p>发布点(唯一):{@code AgentLoopHarness.processOneQuery} 入门处,在 memory injection
 * 之前发出,保证 {@code content} 是**干净原文**(不带 {@code <memories>...</memories>} 前缀)。
 *
 * <p>{@code source} 描述入口:
 * <ul>
 *   <li>{@code "web"} —— Web 前端 ChatController 触发</li>
 *   <li>{@code "cli"} —— JoojCliRunner REPL 触发</li>
 *   <li>{@code "channel:weixin"} / {@code "channel:xxx"} —— 外部渠道触发</li>
 *   <li>{@code "cron:jobId"} —— cron 定时任务触发(s22 B1 refactor 合并进本事件类型,
 *       transcript role 会落 {@code "scheduled"} 而非 {@code "user"},前端按 source 前缀分派)</li>
 * </ul>
 */
public record UserMessageReceived(
        UUID eventId,
        String sessionId,
        String content,
        Instant timestamp,
        String source
) implements TranscriptEvent {
}
