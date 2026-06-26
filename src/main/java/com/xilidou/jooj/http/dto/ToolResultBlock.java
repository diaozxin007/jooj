package com.xilidou.jooj.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具执行结果。要回传给 LLM 时由 user 消息携带。
 *
 * <p>JSON:
 * <pre>
 *   {
 *     "type": "tool_result",
 *     "tool_use_id": "toolu_01XYZ",
 *     "content": "file1.txt\nfile2.txt"
 *   }
 * </pre>
 *
 * <p>关键设计：
 * <ul>
 *   <li>{@code toolUseId} → JSON 字段 {@code tool_use_id}（snake_case 映射）</li>
 *   <li>{@code content} 类型是 {@code Object}，因为可以是：
 *     <ul>
 *       <li>{@code String} - 大多数场景（命令输出、文件内容）</li>
 *       <li>{@code List<ContentBlock>} - 含图片时（vision 场景）</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>⚠️ 注意：tool_result 必须包在 {@code role: "user"} 的消息里，
 * 不是 {@code role: "tool"}（OpenAI 协议才是 tool）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolResultBlock implements ContentBlock {

    @JsonProperty("tool_use_id")
    private String toolUseId;        // 必须匹配上一轮 ToolUseBlock 的 id

    private Object content;          // 通常是 String，vision 场景为 List<ContentBlock>

    /**
     * 便利构造器：纯文本结果。
     */
    public static ToolResultBlock ofText(String toolUseId, String text) {
        return new ToolResultBlock(toolUseId, text);
    }

    @Override
    public String getType() {
        return "tool_result";
    }

}
