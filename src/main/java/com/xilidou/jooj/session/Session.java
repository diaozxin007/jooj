package com.xilidou.jooj.session;

import java.time.Instant;

/**
 * Session — 一段独立的对话上下文。
 *
 * <p>引入 session 抽象的目的是让多个对话能并存而不串味:
 * <ul>
 *   <li>每个 session 自己的 history(独立的 messages list)</li>
 *   <li>每个 session 自己的 lock(不同 session 并行 OK,同 session 互斥)</li>
 *   <li>每个 session 自己的 transcript(持久化在 ~/.jooj/sessions/&lt;id&gt;.json)</li>
 * </ul>
 *
 * <p>memory / tasks / skills / hooks 仍是全局 —— 它们是项目级知识,不该串味。
 *
 * <h3>特殊 ID</h3>
 *
 * <ul>
 *   <li>{@code default} — Web 前端不传 sessionId 时的兜底(向后兼容)</li>
 *   <li>{@code cli-default} — CLI REPL 启动时使用的固定 session</li>
 *   <li>{@code cron-default} — {@code CronQueueProcessor} 触发 LLM run 时收容,不污染交互对话</li>
 * </ul>
 *
 * @param id            UUID(或上述三个特殊字面量)
 * @param title         "New chat 06-29 11:30",用户可改
 * @param createdAt     创建时间
 * @param lastActiveAt  最后一次发送/接收消息的时间
 * @param messageCount  聊天消息条数(便利字段,持久化时由 SessionService 算出来填上)
 */
public record Session(
        String id,
        String title,
        Instant createdAt,
        Instant lastActiveAt,
        int messageCount
) {

    /** Web 默认 session 的固定 ID。 */
    public static final String DEFAULT_ID = "default";

    /** CLI REPL 固定 session 的 ID。 */
    public static final String CLI_DEFAULT_ID = "cli-default";

    /** Cron 触发收容 session 的 ID。 */
    public static final String CRON_DEFAULT_ID = "cron-default";

    /** 三个特殊 ID 不允许被 delete。 */
    public static boolean isReserved(String id) {
        return DEFAULT_ID.equals(id) || CLI_DEFAULT_ID.equals(id) || CRON_DEFAULT_ID.equals(id);
    }
}
