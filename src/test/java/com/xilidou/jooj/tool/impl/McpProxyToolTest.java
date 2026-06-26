package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.mcp.McpRegistry;
import com.xilidou.jooj.mcp.MockMcpTransport;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link McpProxyTool} 的工具暴露 + dispatch 行为。
 *
 * <p>用真 {@link MockMcpTransport} + 真 {@link McpRegistry},不需要 mock。
 */
class McpProxyToolTest {

    private McpProxyTool tool;
    private McpRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new McpRegistry(new MockMcpTransport());
        tool = new McpProxyTool(registry);
    }

    private ToolResult call(String name, Map<String, Object> args) {
        return tool.execute(new ToolCall(name, args));
    }

    // ─────────────────────────────────────────────────────────────
    //  getTools 动态返工具集
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("初始无连接:getTools 只返 connect_mcp")
    void get_tools_no_connections() {
        List<ToolDefinition> defs = tool.getTools();
        assertEquals(1, defs.size());
        assertEquals("connect_mcp", defs.get(0).getName());
    }

    @Test
    @DisplayName("connect 后:getTools 返 connect_mcp + mcp__server__tool")
    void get_tools_after_connect() {
        registry.connect("docs");

        List<String> names = tool.getTools().stream()
                .map(ToolDefinition::getName).toList();
        assertEquals(3, names.size());          // connect + search + get_version
        assertTrue(names.contains("connect_mcp"));
        assertTrue(names.contains("mcp__docs__search"));
        assertTrue(names.contains("mcp__docs__get_version"));
    }

    @Test
    @DisplayName("两个 server 都 connect:getTools 包含双方所有 mcp__ 工具")
    void get_tools_two_servers() {
        registry.connect("docs");
        registry.connect("deploy");

        List<String> names = tool.getTools().stream()
                .map(ToolDefinition::getName).toList();
        assertTrue(names.contains("mcp__docs__search"));
        assertTrue(names.contains("mcp__docs__get_version"));
        assertTrue(names.contains("mcp__deploy__trigger"));
        assertTrue(names.contains("mcp__deploy__status"));
    }

    @Test
    @DisplayName("MCP 工具的 description 保留 (readOnly)/(destructive) 标注")
    void mcp_tool_descriptions_preserve_safety_annotations() {
        registry.connect("docs");
        registry.connect("deploy");

        ToolDefinition search = tool.getTools().stream()
                .filter(d -> "mcp__docs__search".equals(d.getName()))
                .findFirst().orElseThrow();
        assertTrue(search.getDescription().contains("(readOnly)"));

        ToolDefinition trigger = tool.getTools().stream()
                .filter(d -> "mcp__deploy__trigger".equals(d.getName()))
                .findFirst().orElseThrow();
        assertTrue(trigger.getDescription().contains("(destructive"));
    }

    // ─────────────────────────────────────────────────────────────
    //  connect_mcp 工具执行
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("connect_mcp 成功路径")
    void connect_mcp_success() {
        ToolResult r = call("connect_mcp", Map.of("server", "docs"));
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("Connected to MCP server 'docs'"));
        assertEquals(1, registry.connectedCount());
    }

    @Test
    @DisplayName("connect_mcp 缺 server 参数 → success=false")
    void connect_mcp_missing_arg() {
        ToolResult r = call("connect_mcp", Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().toLowerCase().contains("server"));
    }

    @Test
    @DisplayName("connect_mcp 未知 server → success=false")
    void connect_mcp_unknown_server() {
        ToolResult r = call("connect_mcp", Map.of("server", "ghost"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().toLowerCase().contains("unknown"));
    }

    @Test
    @DisplayName("connect_mcp 重复连接同一 server → 仍 success")
    void connect_mcp_duplicate_is_success() {
        call("connect_mcp", Map.of("server", "docs"));
        ToolResult r = call("connect_mcp", Map.of("server", "docs"));
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("already connected"));
    }

    // ─────────────────────────────────────────────────────────────
    //  mcp__server__tool dispatch
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mcp__docs__search 调用 → 通过 transport 拿 mock 响应")
    void dispatch_mcp_search() {
        registry.connect("docs");
        ToolResult r = call("mcp__docs__search", Map.of("query", "kafka"));
        assertTrue(r.isSuccess());
        assertEquals("[docs] Found 3 results for 'kafka'", r.getOutput());
    }

    @Test
    @DisplayName("mcp__deploy__trigger 调用 → destructive 工具也能跑")
    void dispatch_mcp_trigger() {
        registry.connect("deploy");
        ToolResult r = call("mcp__deploy__trigger", Map.of("service", "auth-api"));
        assertTrue(r.isSuccess());
        assertEquals("[deploy] Triggered: auth-api", r.getOutput());
    }

    @Test
    @DisplayName("mcp__xxx 但 server 没 connect → 'tool not found' 友好错误")
    void dispatch_mcp_no_connect() {
        ToolResult r = call("mcp__docs__search", Map.of("query", "x"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("not found"));
        assertTrue(r.getOutput().contains("Connect the server first"));
    }

    @Test
    @DisplayName("非法工具名 → 'Unknown tool: <name>'")
    void unknown_tool() {
        ToolResult r = call("not_a_real_tool", Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("Unknown tool"));
    }
}
