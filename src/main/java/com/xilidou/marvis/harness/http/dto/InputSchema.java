package com.xilidou.marvis.harness.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * JSON Schema for tool input。
 *
 * <p>对应 JSON：
 * <pre>
 *   {
 *     "type": "object",
 *     "properties": {
 *       "command": { "type": "string", "description": "Shell command" }
 *     },
 *     "required": ["command"]
 *   }
 * </pre>
 *
 * <p>{@code properties} 是 {@code Map<String, Object>} 因为字段定义本身可以嵌套
 * （比如某字段是 {@code {"type": "array", "items": {"type": "string"}}}）。
 *
 * <p>静态工厂 {@link #object} 简化常见用法。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InputSchema {

    private String type;                          // 总是 "object"

    private Map<String, Object> properties;       // 字段定义

    private List<String> required;                // 必填字段名

    /**
     * 便利构造：type=object 的 schema。
     *
     * @param properties 字段定义
     * @param required   必填字段名（可变长参数）
     */
    public static InputSchema object(Map<String, Object> properties, String... required) {
        return new InputSchema("object", properties, List.of(required));
    }
}
