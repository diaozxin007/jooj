package com.xilidou.jooj.mcp;

import com.xilidou.jooj.config.JsonMappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link McpRegistry} 的连接 + 工具解析行为。
 *
 * <p>用真 {@link MockMcpTransport}(内置 docs/deploy 两个 mock server)+ 空的
 * {@link McpServerRegistry}(mock server 不需要落盘到 JSON,transport 内自己知道)。
 *
 * <p>M1 (2026-07-14):构造器加了 {@link McpServerRegistry} 参数。
 */
class McpRegistryTest {

    private McpRegistry registry;
    private McpServerRegistry serverRegistry;

    @BeforeEach
    void setUp() throws IOException {
        McpServersJsonStore store = new McpServersJsonStore(JsonMappers.newMapper());
        cleanDir(store.getDir());
        // mock server(docs/deploy)不在 McpServerRegistry 里,故传空 props
        serverRegistry = new McpServerRegistry(new McpProperties(), store);
        registry = new McpRegistry(new MockMcpTransport(), serverRegistry);
    }

    private static void cleanDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) Files.deleteIfExists(p);
        }
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
    //  M1 (2026-07-14):status 落地行为
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("connect 已在 McpServerRegistry 的 server 成功 → 标记 CONNECTED 并落盘")
    void connect_marks_connected_when_in_server_registry() throws IOException {
        // seed 一个 mock-known server 名字到 McpServerRegistry
        // 借用 docs 名字:transport 认识,serverRegistry 也认识
        seedServer("docs");

        String result = registry.connect("docs");
        assertTrue(result.startsWith("Connected"));

        McpServerRecord got = serverRegistry.get("docs").orElseThrow();
        assertEquals(McpServerRecord.Status.CONNECTED, got.status());
        assertNotNull(got.lastConnectedAt());
    }

    @Test
    @DisplayName("connect listTools 抛异常 → catch + markFailed + 返错误字符串(不再抛给 caller)")
    void connect_failure_marks_failed_and_returns_error_string() throws IOException {
        seedServer("faulty");
        McpTransport faultyTransport = new FaultyTransport();
        McpRegistry faultyRegistry = new McpRegistry(faultyTransport, serverRegistry);

        String result = faultyRegistry.connect("faulty");

        assertTrue(result.startsWith("Failed to connect"),
                "M1 后失败路径应返错误字符串,实际:" + result);
        McpServerRecord got = serverRegistry.get("faulty").orElseThrow();
        assertEquals(McpServerRecord.Status.FAILED, got.status());
        assertNotNull(got.lastError());
    }

    @Test
    @DisplayName("connect mock server(不在 McpServerRegistry)→ 不 markConnected,不报错")
    void connect_mock_server_no_registry_touch() {
        // docs 不 seed 到 McpServerRegistry,但 mock transport 认它
        String result = registry.connect("docs");
        assertTrue(result.startsWith("Connected"));
        assertFalse(serverRegistry.contains("docs"),
                "mock server 走连接不应污染 McpServerRegistry");
    }

    private void seedServer(String name) throws IOException {
        McpServerRecord r = new McpServerRecord(
                name, "mock-command", List.of(), Map.of(),
                true, McpServerRecord.Status.NEVER_CONNECTED, null,
                java.time.Instant.now(), null);
        // 直接通过 store 落盘再 rescan,让 McpServerRegistry 认识它
        McpServersJsonStore store = new McpServersJsonStore(JsonMappers.newMapper());
        store.save(r);
        serverRegistry.rescan(true);
    }

    /** Fault-injecting transport:listTools 抛异常。serverExists=true 让流程走到 listTools。 */
    private static class FaultyTransport implements McpTransport {
        @Override public List<McpToolDef> listTools(String serverName) {
            throw new RuntimeException("stdio pipe broken");
        }
        @Override public String callTool(String s, String t, Map<String, Object> a) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean serverExists(String serverName) { return true; }
        @Override public List<String> availableServers() { return List.of("faulty"); }
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
