package com.xilidou.jooj.llm.domain;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Vendor-neutral tool definition.
 *
 * <p>Structure mirrors {@link com.xilidou.jooj.tool.ToolDefinition} but uses a raw
 * {@link JsonNode} schema so adapters can wrap it in the vendor-specific outer
 * shell:
 * <ul>
 *   <li>Anthropic → {@code {"name":..., "description":..., "input_schema": <schema>}}</li>
 *   <li>OpenAI Chat → {@code {"type":"function", "function": {"name":..., "description":..., "parameters": <schema>}}}</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LlmToolDef {

    private String name;
    private String description;
    private JsonNode schema;
}
