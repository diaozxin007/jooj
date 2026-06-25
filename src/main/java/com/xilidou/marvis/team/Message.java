package com.xilidou.marvis.team;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Team 内一条消息 —— 严格对齐上游 s15 5 字段 dict。
 *
 * <p>对应 Python:
 * <pre>
 *   msg = {"from": from_agent, "to": to_agent,
 *          "content": content, "type": msg_type, "ts": time.time()}
 * </pre>
 *
 * <h3>字段</h3>
 *
 * <ul>
 *   <li>{@code from} —— 发送者 agent name(如 {@code "alice"} / {@code "lead"})</li>
 *   <li>{@code to} —— 接收者 agent name(对应一个 mailbox 文件)</li>
 *   <li>{@code content} —— 文本内容</li>
 *   <li>{@code type} —— 消息类型({@code "message"} / {@code "result"} 等),教学版只用文本字段区分;
 *       未来可扩展 {@code "shutdown"} / {@code "permission_request"} 走 s16 协议</li>
 *   <li>{@code ts} —— Unix epoch 毫秒时间戳,用于排序 + 调试</li>
 * </ul>
 *
 * <p>用 Lombok @Data + Jackson 注解,跟 marvis 其他 DTO(TaskRecord / CronJob)同模式 ——
 * 不用 record 是因为 Spring 测试框架装配 / 反序列化更省事。
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

    /**
     * 消息类型,跟上游一致默认 {@code "message"};teammate 完成后汇报 lead 用 {@code "result"}。
     * s16 之后会扩展 shutdown / permission_request 等。
     */
    private String type;

    /** Unix epoch 毫秒时间戳。 */
    private long ts;
}
