package com.xilidou.marvis.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link McpRegistry} 的连接 + 工具解析行为。
 *
 * <p>用真 {@link MockMcpTransport}(内置 docs/deploy 两个 mock server)。
 */
class McpRegistryTest {

    private McpRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new McpRegistry(new MockMcpTransport());
    }

    // ─────────────────────────────────────────────────────────────
    //  connect
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("connect 已知 server → 'Connected to MCP server ...' + 工具列表注册")
    void connect_known_server() {
        String result = registry.connect("docs");
        assertTrue(result.startsWith("Connected to MCP server 'docs'"));
        assertTrue(result.contains("search"));
        assertTrue(result.contains("get_version"));
        assertEquals(1, registry.connectedCount());
    }

    @Test
    @DisplayName("connect 未知 server → 友好错误 + 列出 available")
    void connect_unknown_server() {
        String result = registry.connect("unknown-svc");
        assertTrue(result.startsWith("Unknown server 'unknown-svc'"));
        assertTrue(result.contains("docs"));
        assertTrue(result.contains("deploy"));
        assertEquals(0, registry.connectedCount());
    }

    @Test
    @DisplayName("connect 重复连接同一 server → 'already connected'")
    void connect_duplicate() {
        registry.connect("docs");
        String second = registry.connect("docs");
        assertTrue(second.contains("already connected"));
        assertEquals(1, registry.connectedCount());
    }

    @Test
    @DisplayName("connect blank/null → Error: name must not be blank")
    void connect_blank() {
        assertTrue(registry.connect(null).startsWith("Error:"));
        assertTrue(registry.connect("").startsWith("Error:"));
        assertTrue(registry.connect("   ").startsWith("Error:"));
    }

    @Test
    @DisplayName("两个不同 server 同时连接")
    void connect_two_servers() {
        registry.connect("docs");
        registry.connect("deploy");
        assertEquals(2, registry.connectedCount());

        var clients = registry.allClients();
        var names = clients.stream().map(McpClient::getName).toList();
        assertTrue(names.contains("docs"));
        assertTrue(names.contains("deploy"));
    }

    // ─────────────────────────────────────────────────────────────
    //  prefixedName / normalizeName
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("prefixedName 拼接格式 mcp__<server>__<tool>")
    void prefixed_name_format() {
        assertEquals("mcp__docs__search",
                McpRegistry.prefixedName("docs", "search"));
        assertEquals("mcp__deploy__trigger",
                McpRegistry.prefixedName("deploy", "trigger"));
    }

    @Test
    @DisplayName("normalizeName 把非法字符替换为 _")
    void normalize_name() {
        assertEquals("a_b_c", McpRegistry.normalizeName("a/b.c"));
        assertEquals("hello-world_42",
                McpRegistry.normalizeName("hello-world_42"));   // 合法字符不变
        assertEquals("space_in_name",
                McpRegistry.normalizeName("space in name"));
        assertEquals("", McpRegistry.normalizeName(null));
    }

    @Test
    @DisplayName("normalizeName 应用到 prefix:含特殊字符的 server name 也合法")
    void prefixed_name_normalizes() {
        assertEquals("mcp__my_svc__do_thing",
                McpRegistry.prefixedName("my.svc", "do/thing"));
    }

    // ─────────────────────────────────────────────────────────────
    //  findToolByPrefixedName
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findToolByPrefixedName 找到已连接 server 的工具")
    void find_tool_after_connect() {
        registry.connect("docs");
        var resolved = registry.findToolByPrefixedName("mcp__docs__search");
        assertTrue(resolved.isPresent());
        assertEquals("docs", resolved.get().client().getName());
        assertEquals("search", resolved.get().toolDef().getName());
    }

    @Test
    @DisplayName("findToolByPrefixedName 未连接的 server 返 empty")
    void find_tool_no_connection() {
        var resolved = registry.findToolByPrefixedName("mcp__docs__search");
        assertTrue(resolved.isEmpty());
    }

    @Test
    @DisplayName("findToolByPrefixedName 不带 mcp__ 前缀的名字直接 empty")
    void find_tool_non_mcp_name() {
        registry.connect("docs");
        assertTrue(registry.findToolByPrefixedName("bash").isEmpty());
        assertTrue(registry.findToolByPrefixedName(null).isEmpty());
    }

    @Test
    @DisplayName("findToolByPrefixedName 已连 server 但工具名错 → empty")
    void find_tool_wrong_tool_name() {
        registry.connect("docs");
        assertTrue(registry.findToolByPrefixedName("mcp__docs__nonexistent").isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    //  callTool 通过 client 转发到 transport
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("通过 McpClient.callTool 拿到 mock 响应")
    void call_tool_via_client() {
        registry.connect("docs");
        var resolved = registry.findToolByPrefixedName("mcp__docs__search").orElseThrow();
        String out = resolved.client().callTool("search", Map.of("query", "kafka"));
        assertEquals("[docs] Found 3 results for 'kafka'", out);
    }

    @Test
    @DisplayName("deploy.trigger 调用返回 destructive 工具的 mock 输出")
    void call_destructive_tool() {
        registry.connect("deploy");
        var resolved = registry.findToolByPrefixedName("mcp__deploy__trigger").orElseThrow();
        String out = resolved.client().callTool("trigger", Map.of("service", "billing-api"));
        assertEquals("[deploy] Triggered: billing-api", out);
    }
}
