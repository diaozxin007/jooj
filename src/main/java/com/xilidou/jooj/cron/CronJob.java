package com.xilidou.jooj.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cron 单条记录 —— 严格对齐上游 s14 5 字段 dataclass。
 *
 * <p>对应 Python:
 * <pre>
 *   @dataclass
 *   class CronJob:
 *       id: str
 *       cron: str       # "0 9 * * *"
 *       prompt: str
 *       recurring: bool
 *       durable: bool
 * </pre>
 *
 * <h3>刻意省略的字段(对齐上游)</h3>
 *
 * <ul>
 *   <li>{@code last_fired} —— 上游放在另一个全局 dict {@code _last_fired_at},不在 CronJob 上</li>
 *   <li>{@code metadata} / {@code description} / {@code timezone} —— 工程化扩展会想加,但教学版严格对齐</li>
 *   <li>{@code expires_at} —— 一次性任务 fire 后从 dict 移除即可,不需要持久化过期时间</li>
 * </ul>
 *
 * <p>Jackson 反序列化兼容:{@code @JsonIgnoreProperties(ignoreUnknown = true)} 防加字段崩;
 * 默认 NoArgsConstructor + AllArgsConstructor 让 record-style 构造也能用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CronJob {

    /**
     * 老 5 参数构造器 —— sessionId=null。保留供 cli REPL 直调 / 测试 / Jackson
     * 反序列化老 durable 文件等不带 session 的场景使用。
     */
    public CronJob(String id, String cron, String prompt, boolean recurring, boolean durable) {
        this(id, cron, prompt, recurring, durable, null);
    }

    /** Job ID,形如 {@code cron_<6 位随机数>}(跟 Python 一致,不含 timestamp 防泄露)。 */
    private String id;

    /** 5 字段 cron 表达式,如 {@code "0 9 * * *"}。语法见 {@link CronExpression}。 */
    private String cron;

    /** 触发时注入到 agent_loop 的 user message 文本。 */
    private String prompt;

    /** true=循环触发,false=一次性(fire 后从 scheduled dict 移除)。 */
    private boolean recurring;

    /**
     * true=持久化到 {@code .scheduled_tasks.json},jooj 重启后恢复;
     * false=纯内存,会话结束就丢。
     *
     * <p>实测场景:用户跑 long-lived REPL 时设 false 即可;真要 cron 主动叫醒
     * 跨 session 的工作需要 daemon 化 jooj(超出教学版范围),所以 durable
     * 主要价值是同一个 jooj 重启后保留。
     */
    private boolean durable;

    /**
     * 调度此 cron 的 session id —— fire 时把 prompt 注入回**这个** session,而不是
     * 一律塞进 cron-default 收容 session(s20 Demo 9 修复)。
     *
     * <p>背景:用户在 web session 说"3 分钟后提醒我",前端轮询这个 session 的
     * history 看通知;如果通知被注入到 cron-default,前端永远看不到。
     *
     * <p>null = 未带 session(老 cron 兼容 / cli REPL 直调 / durable 文件迁移老条目),
     * processCronTriggers 兜底注入 cron-default。
     */
    private String sessionId;
}
