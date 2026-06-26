package com.xilidou.marvis.team;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 协议请求状态 —— 严格对齐上游 s16 {@code ProtocolState} dataclass 7 字段。
 *
 * <p>对应 Python:
 * <pre>
 *   @dataclass
 *   class ProtocolState:
 *       request_id: str        # "req_004281"
 *       type: str              # "shutdown" | "plan_approval"
 *       sender: str            # 发起方
 *       target: str            # 接收方
 *       status: str            # pending | approved | rejected
 *       payload: str           # 计划文本或关机原因
 *       created_at: float      # 时间戳
 * </pre>
 *
 * <h3>状态机</h3>
 *
 * <pre>
 *   pending ──approve──▶ approved
 *      │
 *      └──reject───────▶ rejected
 * </pre>
 *
 * <p>非 {@code pending} 状态收到响应会被忽略(防 duplicate response 重复处理)。
 *
 * <h3>设计选择</h3>
 *
 * <ul>
 *   <li>用 {@code @Data} 类(可变)而非 record,跟 marvis 已有 {@link com.xilidou.marvis.tasks.TaskRecord}
 *       / {@link com.xilidou.marvis.cron.CronJob} 同模式 —— 只有 status 字段会变,
 *       不值得为单字段重建对象</li>
 *   <li>{@code type} 用字符串而非 enum —— 严格对齐上游,字段值跟 Message.type 前缀对应</li>
 *   <li>不持久化 —— 协议请求是瞬态的(几秒到几分钟),进程崩溃后 pending 请求作废即可,
 *       跟上游一致</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProtocolState {

    /** 请求 ID,形如 {@code "req_<6位随机数>"},贯穿请求和响应。 */
    private String requestId;

    /**
     * 协议类型字符串,跟 Message.type 前缀对应:
     * <ul>
     *   <li>{@code "shutdown"} → 期望 {@code shutdown_response} 响应</li>
     *   <li>{@code "plan_approval"} → 期望 {@code plan_approval_response} 响应</li>
     * </ul>
     */
    private String type;

    /** 请求发起方 agent name(可能是 lead 或 teammate)。 */
    private String sender;

    /** 请求目标方 agent name。 */
    private String target;

    /**
     * 状态机当前状态:
     * <ul>
     *   <li>{@link #PENDING} —— 已发出,等响应</li>
     *   <li>{@link #APPROVED} —— 收到 approve=true 响应</li>
     *   <li>{@link #REJECTED} —— 收到 approve=false 响应</li>
     * </ul>
     */
    private String status;

    /**
     * 请求附带的内容(plan 文本 / 关机原因 / 等)。
     * 不在 metadata 里走是因为 payload 通常较长,跟简短的 metadata 字段分开。
     */
    private String payload;

    /** 创建时间(Unix epoch ms)。 */
    private long createdAt;

    // ─────────────────────────────────────────────────────────────
    //  状态常量(避免拼写错误)
    // ─────────────────────────────────────────────────────────────

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";

    /** 协议类型常量。 */
    public static final String TYPE_SHUTDOWN = "shutdown";
    public static final String TYPE_PLAN_APPROVAL = "plan_approval";
}
