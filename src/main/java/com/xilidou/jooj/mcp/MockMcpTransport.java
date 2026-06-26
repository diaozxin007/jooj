package com.xilidou.jooj.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Mock MCP transport —— 进程内 hard-coded "server",不真起子进程。
 *
 * <p>对应上游 s19 的 {@code MOCK_SERVERS = {"docs": ..., "deploy": ...}}。
 *
 * <h3>内置 2 个 mock server</h3>
 *
 * <ul>
 *   <li><b>docs</b> —— 文档查询(readOnly):{@code search} / {@code get_version}</li>
 *   <li><b>deploy</b> —— 部署操作(destructive):{@code trigger} / {@code status}</li>
 * </ul>
 *
 * <p>跟上游字段严格一致,description 含 {@code (readOnly)} / {@code (destructive)} 标注。
 *
 * <h3>不做的事</h3>
 *
 * <ul>
 *   <li>不真起子进程(那是 stdio transport,留给后续)</li>
 *   <li>不做 OAuth(那是 http transport,后续)</li>
 *   <li>不做 reverse channel notification(real CC 才有)</li>
 * </ul>
 *
 * <p>教学版用 mock 的好处:不依赖外部进程就能跑完整 MCP 流程。代价:看不到真正的
 * JSON-RPC 通信和进程管理。这是合理的简化。
 */
@Component
@Slf4j
public class MockMcpTransport implements McpTransport {

    /** server name → (tool defs, handler map) 静态注册表。 */
    private final Map<String, MockServer> servers = new LinkedHashMap<>();

    public MockMcpTransport() {
        servers.put("docs", buildDocsServer());
        servers.put("deploy", buildDeployServer());
    }

    @Override
    public List<McpToolDef> listTools(String serverName) {
        MockServer s = servers.get(serverName);
        if (s == null) {
            throw new IllegalArgumentException("Unknown MCP server: " + serverName);
        }
        return Collections.unmodifiableList(s.tools);
    }

    @Override
    public String callTool(String serverName, String toolName, Map<String, Object> args) {
        MockServer s = servers.get(serverName);
        if (s == null) return "MCP error: unknown server '" + serverName + "'";
        Function<Map<String, Object>, String> handler = s.handlers.get(toolName);
        if (handler == null) return "MCP error: unknown tool '" + toolName + "' on server '" + serverName + "'";
        try {
            return handler.apply(args != null ? args : Map.of());
        } catch (Exception e) {
            log.warn("[MCP mock] {}.{} threw: {}", serverName, toolName, e.toString());
            return "MCP error: " + e.getMessage();
        }
    }

    @Override
    public boolean serverExists(String serverName) {
        return servers.containsKey(serverName);
    }

    @Override
    public List<String> availableServers() {
        return List.copyOf(servers.keySet());
    }

    // ─────────────────────────────────────────────────────────────
    //  Mock server 定义
    // ─────────────────────────────────────────────────────────────

    private static MockServer buildDocsServer() {
        Map<String, Object> searchSchema = Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query")
        );
        Map<String, Object> versionSchema = Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
        );
        List<McpToolDef> tools = List.of(
                new McpToolDef("search", "Search documentation. (readOnly)", searchSchema),
                new McpToolDef("get_version", "Get API version. (readOnly)", versionSchema)
        );
        Map<String, Function<Map<String, Object>, String>> handlers = new LinkedHashMap<>();
        handlers.put("search", args -> {
            Object q = args.get("query");
            return "[docs] Found 3 results for '" + q + "'";
        });
        handlers.put("get_version", args -> "[docs] API v2.1.0");
        return new MockServer(tools, handlers);
    }

    private static MockServer buildDeployServer() {
        Map<String, Object> serviceSchema = Map.of(
                "type", "object",
                "properties", Map.of("service", Map.of("type", "string")),
                "required", List.of("service")
        );
        List<McpToolDef> tools = List.of(
                new McpToolDef("trigger",
                        "Trigger a deployment. (destructive — requires approval in real CC)",
                        serviceSchema),
                new McpToolDef("status", "Check deployment status. (readOnly)", serviceSchema)
        );
        Map<String, Function<Map<String, Object>, String>> handlers = new LinkedHashMap<>();
        handlers.put("trigger", args -> {
            Object svc = args.get("service");
            return "[deploy] Triggered: " + svc;
        });
        handlers.put("status", args -> {
            Object svc = args.get("service");
            return "[deploy] " + svc + ": running (v1.4.2)";
        });
        return new MockServer(tools, handlers);
    }

    /** 内部 record:每个 mock server 含工具定义列表 + handler map。 */
    private record MockServer(List<McpToolDef> tools,
                              Map<String, Function<Map<String, Object>, String>> handlers) {
    }
}
