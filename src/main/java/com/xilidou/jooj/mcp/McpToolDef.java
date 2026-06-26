package com.xilidou.jooj.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * MCP server 暴露的单个工具定义 —— {@code tools/list} 响应的元素。
 *
 * <h3>对应 MCP spec / 上游 s19 字段</h3>
 *
 * <ul>
 *   <li>{@code name} —— server 内部的工具原始名(不带 {@code mcp__} 前缀)</li>
 *   <li>{@code description} —— 工具描述,可能含 {@code (readOnly)} / {@code (destructive)} 标注</li>
 *   <li>{@code inputSchema} —— JSON Schema(同 Anthropic Tool 协议)</li>
 * </ul>
 *
 * <p>jooj 在 {@link McpProxyTool} 里把 server 原始 name 包装成
 * {@code mcp__<server>__<tool>} 暴露给 LLM,避免不同 server 工具名冲突。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpToolDef {

    /** server 内部原始工具名(不含 {@code mcp__} 前缀)。 */
    private String name;

    /** 工具描述,可含 (readOnly) / (destructive) 教学版安全标注。 */
    private String description;

    /** JSON Schema —— properties / required,跟 Anthropic Tool 协议同结构。 */
    private Map<String, Object> inputSchema;
}
