package com.xilidou.jooj.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cron 单条记录 —— self-describing 设计(s21 Demo 20)。
 *
 * <h3>设计原则</h3>
 *
 * <p>**任何时候读取 cron job 都能知道下一步做什么** —— 不依赖 jooj 内存反查表 / session 旁路状态。
 * 投递信息(deliveryType / channel / peerId)在 schedule 时 freeze 进 job,fire 时直接读。
 *
 * <p>对应 Hermes Agent 的 {@code "deliver"} / {@code "origin"} 设计:
 * "Jobs stored in ~/.hermes/cron/jobs.json, with delivery target frozen at create-time"。
 *
 * <h3>字段</h3>
 *
 * <p>**核心 5 个**(原 s14 上游对齐):id / cron / prompt / recurring / durable
 *
 * <p>**路由 4 个**(s20 Demo 9 + s21 Demo 20):
 * <ul>
 *   <li>{@code sessionId} — 跑 agentLoop 用哪条 session(注入 prompt 的目的地)</li>
 *   <li>{@code deliveryType} — 投递策略:{@code "channel"} / {@code "team"} / {@code "none"}</li>
 *   <li>{@code channel} — 当 deliveryType=channel:回写哪个 IM 渠道(weixin/discord)</li>
 *   <li>{@code peerId} — 当 deliveryType=channel:回给哪个对端(原始 raw 形式,如 xxx@im.wechat)</li>
 * </ul>
 *
 * <h3>Jackson 兼容</h3>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown=true)} 防加字段崩;
 * {@code @NoArgsConstructor} 让反序列化能跑;反序列化时缺失字段为 null/false 默认值。
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CronJob {

    /** Job ID,形如 {@code cron_<6 位随机数>}(跟 Python 一致,不含 timestamp 防泄露)。 */
    private String id;

    /** 5 字段 cron 表达式,如 {@code "0 9 * * *"}。语法见 {@link CronExpression}。 */
    private String cron;

    /** 触发时注入到 agent_loop 的 user message 文本。 */
    private String prompt;

    /** true=循环触发,false=一次性(fire 后从 scheduled dict 移除)。 */
    private boolean recurring;

    /** true=持久化到 {@code .scheduled_tasks.json},jooj 重启后恢复;false=纯内存。 */
    private boolean durable;

    /**
     * 调度此 cron 的 session id —— fire 时把 prompt 注入回**这个** session(s20 Demo 9)。
     * null 表示走兜底 cron-default。
     */
    private String sessionId;

    /**
     * 投递策略(s21 Demo 20):
     * <ul>
     *   <li>{@code "channel"} — 回写到 IM channel(用 channel + peerId)</li>
     *   <li>{@code "team"} — 投递给 agent mailbox(用 agentName,Tier B 后续)</li>
     *   <li>{@code "none"} 或 null — 不投递,LLM 跑完结束(cron-default session 老行为)</li>
     * </ul>
     */
    private String deliveryType;

    /** 当 {@code deliveryType="channel"} 时:目标渠道名,如 {@code "weixin"}。 */
    private String channel;

    /**
     * 当 {@code deliveryType="channel"} 时:对端 ID,**原始 raw 形式**(如 {@code "xxx@im.wechat"}),
     * 不是 sessionId 用的 sanitized 版本。直接给 channel.sendOutbound 用。
     */
    private String peerId;

    /** 完整 ctor —— Jackson 用,代码侧 schedule 时通过 setter 设字段。 */
    public CronJob(String id, String cron, String prompt, boolean recurring, boolean durable,
                   String sessionId, String deliveryType, String channel, String peerId) {
        this.id = id;
        this.cron = cron;
        this.prompt = prompt;
        this.recurring = recurring;
        this.durable = durable;
        this.sessionId = sessionId;
        this.deliveryType = deliveryType;
        this.channel = channel;
        this.peerId = peerId;
    }

    /**
     * 测试 / CLI 便利 ctor:不带 sessionId / delivery 字段(等价 deliveryType="none",
     * 跑去 cron-default session)。生产代码 schedule cron 应走全参 ctor 或 CronService.schedule。
     */
    public CronJob(String id, String cron, String prompt, boolean recurring, boolean durable) {
        this(id, cron, prompt, recurring, durable, null, "none", null, null);
    }

    /** 测试便利 ctor:带 sessionId 但无 delivery。 */
    public CronJob(String id, String cron, String prompt, boolean recurring, boolean durable,
                   String sessionId) {
        this(id, cron, prompt, recurring, durable, sessionId, "none", null, null);
    }
}
