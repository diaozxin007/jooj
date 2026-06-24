package com.xilidou.marvis.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Anthropic 工具定义（请求 tools 数组里的元素）。
 *
 * <p>对应 JSON：
 * <pre>
 *   {
 *     "name": "bash",
 *     "description": "Run a shell command.",
 *     "input_schema": {
 *       "type": "object",
 *       "properties": {"command": {"type": "string"}},
 *       "required": ["command"]
 *     }
 *   }
 * </pre>
 *
 * <p>注意 {@code inputSchema} → JSON 字段 {@code input_schema}（snake_case）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolDef {

    private String name;

    private String description;

    @JsonProperty("input_schema")
    private InputSchema inputSchema;

}
