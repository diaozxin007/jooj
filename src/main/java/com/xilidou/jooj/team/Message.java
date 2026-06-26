package com.xilidou.jooj.team;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Team 内一条消息 —— s16 升级为 6 字段(增 metadata)。
 *
 * <p>对应 Python:
 * <pre>
 *   msg = {"from": from_agent, "to": to_agent,
 *          "content": content, "type": msg_type, "ts": time.time(),
 *          "metadata": {"request_id": ..., "approve": ..., ...}}
 * </pre>
 *
 * <h3>字段</h3>
 *
 * <ul>
 *   <li>{@code from} —— 发送者 agent name</li>
 *   <li>{@code to} —— 接收者 agent name(对应一个 mailbox 文件)</li>
 *   <li>{@code content} —— 文本内容</li>
 *   <li>{@code type} —— 消息类型:
 *     <ul>
 *       <li>{@code "message"} —— 普通文本消息</li>
 *       <li>{@code "result"} —— teammate 完成后汇报 lead</li>
 *       <li>{@code "shutdown_request"} / {@code "shutdown_response"} —— s16 关机协议</li>
 *       <li>{@code "plan_approval_request"} / {@code "plan_approval_response"} —— s16 计划审批</li>
 *     </ul>
 *   </li>
 *   <li>{@code ts} —— Unix epoch 毫秒时间戳</li>
 *   <li>{@code metadata} —— s16 新增:协议消息的结构化字段(request_id / approve 等)。
 *       普通消息留空 map。跟上游用 {@code Map<String, Object>} 严格一致。
 *       <b>权衡</b>:用强类型 sealed interface 类型安全更好,但跟上游字段对不上;教学版选 Map。
 *       后期优化清单已记录。</li>
 * </ul>
 *
 * <p>构造器:
 * <ul>
 *   <li>{@code @AllArgsConstructor}(6 参)用于 Jackson 反序列化 + s16 协议路径</li>
 *   <li>{@link #Message(String, String, String, String, long)}(5 参)向后兼容 s15 调用点,
 *       自动初始化 metadata 为空 map</li>
 * </ul>
 *
 * <p>用 Lombok @Data + Jackson 注解,跟 jooj 其他 DTO(TaskRecord / CronJob)同模式。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message {

    /** 发送者 agent name。 */
    private String from;

    /** 接收者 agent name。 */
    private String to;

    /** 文本内容。 */
    private String content;

    /** 消息类型(见类 javadoc)。 */
    private String type;

    /** Unix epoch 毫秒时间戳。 */
    private long ts;

    /**
     * s16 新增:协议消息的结构化字段。
     *
     * <p>典型用法:
     * <pre>
     *   shutdown_request:  {"request_id": "req_004281"}
     *   shutdown_response: {"request_id": "req_004281", "approve": true}
     *   plan_approval_request: {"request_id": "req_007123"}
     *   plan_approval_response: {"request_id": "req_007123", "approve": false, "feedback": "..."}
     * </pre>
     *
     * <p>普通消息(type="message" / "result")留空 map 即可。
     */
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 5 参便利构造器(向后兼容 s15 调用点)。
     * 自动把 metadata 初始化为空 map。
     */
    public Message(String from, String to, String content, String type, long ts) {
        this(from, to, content, type, ts, new HashMap<>());
    }
}
