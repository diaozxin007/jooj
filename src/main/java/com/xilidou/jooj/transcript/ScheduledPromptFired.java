package com.xilidou.jooj.transcript;

import java.time.Instant;
import java.util.UUID;

/**
 * Cron 定时触发事件 —— D7 独立事件,不伪装 user。
 *
 * <p>Transcript 里落成 {@code role="scheduled"},前端可渲染为"⏰ Scheduled by cron:job-42"
 * 系统气泡,跟用户敲的 user 气泡明确区分,便于审计。
 *
 * <p>发布点(唯一):{@code AgentLoopHarness.processCronTriggers} 里对每个 fired job 各发一次。
 * 注意 LLM 视图仍然会加 {@code "[Scheduled] "} 前缀让 model 知道是定时触发,
 * 但 transcript 事件里的 {@code prompt} 字段是**干净原文**(不带前缀)。
 *
 * <p>{@code jobId} 用于前端展示 + 未来审计追溯到原 CronJob 定义。
 */
public record ScheduledPromptFired(
        UUID eventId,
        String sessionId,
        String prompt,
        String jobId,
        Instant timestamp
) implements TranscriptEvent {
}
