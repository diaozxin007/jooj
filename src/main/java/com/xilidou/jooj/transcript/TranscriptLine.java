package com.xilidou.jooj.transcript;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Transcript 里存的一行 —— 落盘到 {@code transcripts/<sid>.jsonl} 每一行的 JSON shape。
 *
 * <p>字段:
 * <ul>
 *   <li>{@code role} —— {@code "user"} / {@code "scheduled"} / {@code "assistant"};
 *       未来可扩展 {@code "system"} / {@code "webhook"}</li>
 *   <li>{@code content} —— 消息文本(user 是干净原文,scheduled 是 cron prompt,assistant 是最终回复)</li>
 *   <li>{@code timestamp} —— 事件发生时间</li>
 *   <li>{@code source} —— 来源 hint(user 类型有 "web"/"cli"/"channel:xxx";scheduled 类型是 "cron:jobId";
 *       assistant 类型为 null)—— Jackson 用 {@link JsonInclude.Include#NON_NULL} 跳过写入</li>
 * </ul>
 *
 * <p><b>为什么不存 eventId</b>:D11 幂等靠 TranscriptService 内存 LRU 就够,transcript 文件本身
 * 面向 grep + 人眼阅读,加 UUID 字段污染可读性;未来审计需要基于 eventId 追踪,
 * append-only 加字段兼容。
 */
public record TranscriptLine(
        String role,
        String content,
        Instant timestamp,
        @JsonInclude(JsonInclude.Include.NON_NULL) String source
) {
}
