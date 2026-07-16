package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.mcp.McpClient;
import com.xilidou.jooj.mcp.McpRegistry;
import com.xilidou.jooj.mcp.McpToolDef;
import com.xilidou.jooj.tool.Tool;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * McpProxyTool —— 把 MCP 协议接进 jooj 工具系统。
 *
 * <h3>暴露给 LLM 的工具(动态)</h3>
 *
 * <ul>
 *   <li>{@code connect_mcp(server)} —— 连接一个 MCP server,发现工具。永远暴露</li>
 *   <li>{@code mcp__<server>__<tool>} —— 已连接 server 的所有工具,**动态**根据
 *       {@link McpRegistry} 当前状态生成。LLM 第一次调 connect_mcp 后,下一轮
 *       agentLoop.buildTools 重新读 ToolRegistry.getAllTools 时,会看到新增的 mcp__ 工具</li>
 * </ul>
 *
 * <h3>设计选择:单 @Component 而非动态注册到 ToolRegistry</h3>
 *
 * <p>jooj ToolRegistry 是 Spring 装配时一次性收集 {@code @Component Tool} 实现。
 * 运行期动态加 / 删 Tool 不优雅。改用单 McpProxyTool 内部管理"动态工具集" ——
 * {@link #getTools()} 每次返回 (connect_mcp + 所有 mcp__xxx),
 * {@link #execute} 按 toolName 派发。
 *
 * <p>优势:
 * <ul>
 *   <li>Spring 装配不动</li>
 *   <li>新连一个 server 后,下次 buildTools 自动发现新工具(无需重启)</li>
 *   <li>所有 MCP 相关逻辑收在一个文件,职责清晰</li>
 * </ul>
 */
@Component
@Slf4j
public class McpProxyTool implements Tool {

    private static final String CONNECT_TOOL = "connect_mcp";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";

    private final McpRegistry registry;

    public McpProxyTool(McpRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getName() {
        return "mcp";
    }

    @Override
    public String getDescription() {
        return "MCP plugin system: connect_mcp + dynamically discovered mcp__server__tool tools.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        List<ToolDefinition> out = new ArrayList<>();

        // 1. connect_mcp 永远暴露
        Map<String, Object> connectSchema = Map.of(
                "server", Map.of("type", "string",
                        "description", "MCP server name to connect to (e.g. 'docs', 'deploy')")
        );
        out.add(new ToolDefinition(
                CONNECT_TOOL,
                "Connect to an MCP server and discover its tools. " +
                        "After connect, server's tools become available as 'mcp__<server>__<tool>'.",
                InputSchema.object(connectSchema, "server")
        ));

        // 2. 已连接 server 的工具,转成 ToolDefinition,带 mcp__ 前缀
        for (McpClient client : registry.allClients()) {
            for (McpToolDef def : client.getTools()) {
                String prefixed = McpRegistry.prefixedName(client.getName(), def.getName());
                out.add(new ToolDefinition(
                        prefixed,
                        def.getDescription() != null ? def.getDescription() : "",
                        toJoojSchema(def.getInputSchema())
                ));
            }
        }
        return out;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String name = call.getToolName();
        if (CONNECT_TOOL.equals(name)) {
            return doConnect(call);
        }
        if (name != null && name.startsWith(McpRegistry.PREFIX)) {
            return doMcpDispatch(name, call.getArguments());
        }
        return new ToolResult(false, "Unknown tool: " + name);
    }

    // ─────────────────────────────────────────────────────────────
    //  handlers
    // ─────────────────────────────────────────────────────────────

    private ToolResult doConnect(ToolCall call) {
        Object server = call.getArguments().get("server");
        if (server == null || server.toString().isBlank()) {
            return new ToolResult(false, "Error: 'server' is required");
        }
        String result = registry.connect(server.toString());
        boolean success = result.startsWith("Connected to MCP server '")
                || result.contains("already connected");
        if (success) {
            log.info("[McpProxyTool] {}", result);
        }
        return new ToolResult(success, result);
    }

    private ToolResult doMcpDispatch(String prefixedName, Map<String, Object> args) {
        Optional<McpRegistry.ResolvedMcpCall> resolved =
                registry.findToolByPrefixedName(prefixedName);
        if (resolved.isEmpty()) {
            return new ToolResult(false,
                    "MCP error: tool '" + prefixedName + "' not found. " +
                            "Connect the server first via connect_mcp.");
        }
        McpRegistry.ResolvedMcpCall r = resolved.get();
        String output = r.client().callTool(r.toolDef().getName(), args);
        boolean success = !output.startsWith("MCP error:");
        return new ToolResult(success, output);
    }

    // ─────────────────────────────────────────────────────────────
    //  内部
    // ─────────────────────────────────────────────────────────────

    /**
     * 把 MCP server 返回的 inputSchema(原始 JSON Schema map)转成 jooj 的
     * {@link InputSchema}(struct)。
     *
     * <p>逻辑:
     * <ul>
     *   <li>有 properties + required 字段 → 直接拷</li>
     *   <li>缺字段或为空 → 退化成 type=object 空对象</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static InputSchema toJoojSchema(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return InputSchema.object(new LinkedHashMap<>());
        }
        Object props = raw.get("properties");
        Map<String, Object> properties = (props instanceof Map<?, ?> m)
                ? (Map<String, Object>) m
                : new LinkedHashMap<>();

        Object req = raw.get("required");
        String[] required;
        if (req instanceof List<?> l) {
            required = l.stream().map(Object::toString).toArray(String[]::new);
        } else {
            required = new String[0];
        }
        return InputSchema.object(properties, required);
    }
}
