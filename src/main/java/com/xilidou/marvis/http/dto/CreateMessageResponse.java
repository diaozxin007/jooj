package com.xilidou.marvis.http.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Optional;

/**
 * Anthropic Messages API 响应。
 *
 * <p>对应 JSON：
 * <pre>
 *   {
 *     "id": "msg_01ABC",
 *     "type": "message",
 *     "role": "assistant",
 *     "content": [...],                              ← 多态：text / tool_use
 *     "model": "claude-sonnet-4-6",
 *     "stop_reason": "tool_use" | "end_turn" | "max_tokens" | "stop_sequence",
 *     "stop_sequence": null,
 *     "usage": { "input_tokens": 142, "output_tokens": 47 }
 *   }
 * </pre>
 *
 * <p>关键设计：
 * <ul>
 *   <li>{@code @JsonIgnoreProperties(ignoreUnknown = true)} - 兼容 Anthropic 加新字段</li>
 *   <li>{@code @JsonProperty} - 驼峰转蛇形（stopReason → stop_reason）</li>
 *   <li>3 个业务方法 - 让 Loop 派发逻辑更清晰</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateMessageResponse {

    private String id;                    // msg_01ABC

    private String type;                  // 总是 "message"

    private String role;                  // 总是 "assistant"

    private List<ContentBlock> content;   // 多态数组

    private String model;                 // 实际使用的模型

    @JsonProperty("stop_reason")
    private String stopReason;

    @JsonProperty("stop_sequence")
    private String stopSequence;

    private Usage usage;

    /**
     * 是否需要继续 loop（模型在调工具）。
     *
     * <p>Loop 派发的核心判断点：
     * <pre>
     *   if (!resp.needsToolExecution()) return;  // end_turn / max_tokens / stop_sequence
     * </pre>
     */
    public boolean needsToolExecution() {
        return "tool_use".equals(stopReason);
    }

    /**
     * 提取第一个文本块（给 UI 展示用）。
     */
    public String firstText() {
        if (content == null) return "";
        return content.stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .findFirst()
                .orElse("");
    }

    /**
     * 提取所有 tool_use 块（给 loop 派发工具用）。
     *
     * <p>用法：
     * <pre>
     *   for (ToolUseBlock tu : resp.toolUses()) {
     *       Map&lt;String, Object&gt; args = mapper.convertValue(tu.getInput(), Map.class);
     *       ToolResult result = registry.execute(new ToolCall(tu.getName(), args));
     *       toolResults.add(ToolResultBlock.ofText(tu.getId(), result.getOutput()));
     *   }
     * </pre>
     */
    public List<ToolUseBlock> toolUses() {
        if (content == null) return List.of();
        return content.stream()
                .filter(b -> b instanceof ToolUseBlock)
                .map(b -> (ToolUseBlock) b)
                .toList();
    }

    /**
     * usage 极少为 null，但防御性返回 Optional 避免 NPE。
     */
    public Optional<Usage> usageOpt() {
        return Optional.ofNullable(usage);
    }
}
