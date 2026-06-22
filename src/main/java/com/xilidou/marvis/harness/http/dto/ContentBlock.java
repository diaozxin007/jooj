package com.xilidou.marvis.harness.http.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Anthropic 消息中的 content block，目前已知 4 种类型：
 * <ul>
 *   <li>{@link TextBlock} - 普通文本块（assistant 输出）</li>
 *   <li>{@link ToolUseBlock} - 工具调用请求（assistant 输出）</li>
 *   <li>{@link ToolResultBlock} - 工具执行结果（user 输入回传）</li>
 *   <li>{@link ThinkingBlock} - Extended Thinking 块（Claude Sonnet 4.x+ 推理过程）</li>
 * </ul>
 *
 * <p>Jackson 通过 {@code type} 字段自动派发到具体子类。
 *
 * <p>JSON 示例：
 * <pre>
 *   {"type": "text",        "text": "Hello"}
 *   {"type": "tool_use",    "id": "toolu_xxx", "name": "bash", "input": {...}}
 *   {"type": "tool_result", "tool_use_id": "toolu_xxx", "content": "..."}
 *   {"type": "thinking",    "thinking": "...", "signature": "..."}
 * </pre>
 *
 * <p>关键防御：{@code defaultImpl = UnknownBlock.class} —— 当 Anthropic 将来加新 type
 * （比如 image / document / mcp_tool_use），不会让反序列化炸。
 * 未知 block 会被转成 {@link UnknownBlock}，loop 派发时自然忽略。
 *
 * <p>关键修复：{@code visible = true} —— 没有这个，Jackson 反序列化时会"吃掉"
 * type 字段（认为它已经用过了），序列化回去时 type 就消失了，导致 Anthropic 收到
 * 不合规的 thinking block 报 502/400。每个实现类必须自己提供 {@code getType()}。
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        visible = true,
        defaultImpl = UnknownBlock.class
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class,       name = "text"),
        @JsonSubTypes.Type(value = ToolUseBlock.class,    name = "tool_use"),
        @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
        @JsonSubTypes.Type(value = ThinkingBlock.class,   name = "thinking"),
})
public interface ContentBlock {

    /**
     * Anthropic 协议要求每个 block 都有 type 字段。
     * 实现类要么硬编码（如 TextBlock 总是 "text"），要么持有此字段（如 UnknownBlock）。
     */
    String getType();
}
