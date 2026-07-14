package com.xilidou.jooj.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
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
 *   <li>{@link #toolResultsWithNotifications(List, List)} - s13 Background Tasks:
 *       同一条 user message 里 tool_result + {@code <task_notification>} 文本块</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageParam {

    private String role;        // "user" / "assistant"

    /**
     * String 或 List<ContentBlock>。
     *
     * <p>{@link MessageContentDeserializer} 保证从 JSON 读回时:
     * <ul>
     *   <li>字符串形态 → String</li>
     *   <li>数组形态 → List<ContentBlock>(通过 ContentBlock 上的 @JsonTypeInfo 正确派发)</li>
     * </ul>
     * 不加这个 deserializer 时 Jackson 会把数组降级成 ArrayList&lt;LinkedHashMap&gt;,
     * 导致 {@code instanceof ToolUseBlock/ToolResultBlock} 全部 miss,
     * HistoryScrubber 净化不到磁盘上的孤儿 tool_use / tool_result。
     */
    @JsonDeserialize(using = MessageContentDeserializer.class)
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

    /**
     * s13 Background Tasks —— 把 tool 执行结果 + 后台完成通知合并到同一条 user message。
     *
     * <p>对应上游 s13 的关键消息形态:同一条 {@code role: "user"} 里既有
     * {@code tool_result} blocks(本轮同步执行的工具)又有
     * {@code <task_notification>} 文本块(此前派出去的 bg task 已完成)。
     *
     * <p>合并顺序:tool_result 在前,task_notification 在后(让 LLM 先消化本轮工具结果,
     * 再看后台异步通知)。
     *
     * <p><b>不变量</b>:返回 {@code List<ContentBlock>} 形态的 content,而非 String,
     * 让 Anthropic API 协议看到的是多块结构化输入。
     *
     * <p>当 {@code notifications} 为空时,行为退化为 {@link #toolResults}(直接传 results 列表)
     * —— 让上游调用方可以无条件调用此 factory,不需要先判空。
     *
     * @param results       本轮工具执行结果(可空但通常至少有一条)
     * @param notifications 后台完成通知(可空)
     */
    public static MessageParam toolResultsWithNotifications(
            List<ToolResultBlock> results,
            List<TextBlock> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return toolResults(results);
        }
        List<ContentBlock> combined = new ArrayList<>();
        if (results != null) combined.addAll(results);
        combined.addAll(notifications);
        return new MessageParam("user", combined);
    }
}
