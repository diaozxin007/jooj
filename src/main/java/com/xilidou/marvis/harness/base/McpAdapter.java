package com.xilidou.marvis.harness.base;

import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;

import java.util.Map;

public class McpAdapter {

    private final SkillRegistry registry;

    public McpAdapter(SkillRegistry registry) {
        this.registry = registry;
    }

    /**
     * MCP tools/list 响应：返回所有可用工具的 JSON Schema
     * 实际 MCP 实现中，这是通过 JSON-RPC 返回的结构
     */
    public String toolsList() {
        StringBuilder sb = new StringBuilder();
        sb.append("[MCP] tools/list response:\n");
        for (ToolDefinition tool : registry.getAllTools()) {
            sb.append(String.format("  { \"name\": \"%s\", \"description\": \"%s\" }\n",
                    tool.getName(), tool.getDescription()));
        }
        return sb.toString();
    }

    /**
     * MCP tools/call 响应：执行工具调用并返回结果
     */
    public String toolsCall(String toolName, Map<String, Object> args) {
        ToolResult result = registry.execute(new ToolCall(toolName, args));
        return String.format("[MCP] tools/call {\"name\": \"%s\"} -> %s: %s",
                toolName, result.isSuccess() ? "success" : "error", result.getOutput());
    }
}
