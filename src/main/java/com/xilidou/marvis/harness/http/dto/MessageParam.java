package com.xilidou.marvis.harness.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一条 message。
 *
 * <p>对应 JSON：
 * <pre>
 *   普通文本：{"role": "user", "content": "Hello"}
 *
 *   含 tool_use（assistant）：
 *   {"role": "assistant", "content": [
 *       {"type": "text", "text": "I'll list files"},
 *       {"type": "tool_use", "id": "...", "name": "bash", "input": {...}}
 *   ]}
 *
 *   含 tool_result（user）：
 *   {"role": "user", "content": [
 *       {"type": "tool_result", "tool_use_id": "...", "content": "..."}
 *   ]}
 * </pre>
 *
 * <p>关键设计：{@code content} 用 {@code Object} 而不是具体类型，因为它可以是：
 * <ul>
 *   <li>{@code String} - 简单文本（用户首次输入）</li>
 *   <li>{@code List<ContentBlock>} - 多块内容（含 tool_use 或 tool_result）</li>
 * </ul>
 *
 * <p>三个静态工厂帮你做对：
 * <ul>
 *   <li>{@link #user(String)} - 普通用户输入</li>
 *   <li>{@link #assistant(List)} - 把 LLM 返回的 content 数组原样回传（坑 4）</li>
 *   <li>{@link #toolResults(List)} - tool 执行结果（注意 role 是 user，不是 tool！坑 3）</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageParam {

    private String role;        // "user" / "assistant"

    private Object content;     // String 或 List<ContentBlock>

    /**
     * 普通用户输入（首次提问）。
     */
    public static MessageParam user(String text) {
        return new MessageParam("user", text);
    }

    /**
     * 把 LLM 返回的 content 数组原样回传到下一轮。
     *
     * <p>⚠️ 必须包含**所有** text + tool_use blocks，不能只回传 tool_use。
     */
    public static MessageParam assistant(List<ContentBlock> blocks) {
        return new MessageParam("assistant", blocks);
    }

    /**
     * 把 tool 执行结果回传给 LLM。
     *
     * <p>⚠️ role 是 {@code "user"}，不是 {@code "tool"}（OpenAI 协议才用 tool）。
     */
    public static MessageParam toolResults(List<ToolResultBlock> results) {
        return new MessageParam("user", results);
    }
}
