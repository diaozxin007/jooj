package com.xilidou.marvis.harness.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具调用请求。assistant 决定调用某个工具时输出。
 *
 * <p>JSON:
 * <pre>
 *   {
 *     "type": "tool_use",
 *     "id": "toolu_01XYZ",
 *     "name": "bash",
 *     "input": {"command": "ls -la"}
 *   }
 * </pre>
 *
 * <p>关键设计：{@code input} 用 {@link JsonNode} 而不是 {@code Map<String, Object>}，
 * 因为：
 * <ol>
 *   <li>不同工具的 input schema 不同，Map 类型不安全</li>
 *   <li>JsonNode 让 Jackson 序列化更可控（不会有 LinkedHashMap 类型转换问题）</li>
 *   <li>调用方可以按需 {@code mapper.convertValue(input, MyArgs.class)} 转成强类型</li>
 * </ol>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolUseBlock implements ContentBlock {

    private String id;       // toolu_01XYZ，必须保留以匹配后续 tool_result.tool_use_id

    private String name;     // 工具名

    private JsonNode input;  // 工具参数（任意 JSON 对象）

    @Override
    public String getType() {
        return "tool_use";
    }
}
