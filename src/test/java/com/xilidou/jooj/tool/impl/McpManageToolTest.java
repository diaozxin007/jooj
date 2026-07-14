package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.mcp.McpProperties;
import com.xilidou.jooj.mcp.McpServerRecord;
import com.xilidou.jooj.mcp.McpServerRegistry;
import com.xilidou.jooj.mcp.McpServersJsonStore;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link McpManageTool} 的 4 actions + 守门行为。
 *
 * <p>surefire 会注入 {@code JOOJ_HOME=target/.jooj-test},本测试在
 * {@code target/.jooj-test/mcp-servers/} 目录下跑,不污染真 {@code ~/.jooj/}。
 */
class McpManageToolTest {

    private McpServerRegistry registry;
    private McpManageTool tool;
    private McpServersJsonStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new McpServersJsonStore(JsonMappers.newMapper());
        cleanDir(store.getDir());
        registry = new McpServerRegistry(new McpProperties(), store);
        tool = new McpManageTool(registry);
    }

    private static void cleanDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) Files.deleteIfExists(p);
        }
    }

    private ToolResult call(Map<String, Object> args) {
        return tool.execute(new ToolCall("mcp_manage", args));
    }

    // ── action 缺失 / 未知 ──

    @Test
    @DisplayName("缺 action → 报友好错")
    void missing_action() {
        ToolResult r = call(Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().toLowerCase().contains("action"));
    }

    @Test
    @DisplayName("未知 action → 提示合法值")
    void unknown_action() {
        ToolResult r = call(Map.of("action", "restart"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("Unknown action"));
    }

    @Test
    @DisplayName("toolName 不匹配 → Unknown tool")
    void wrong_tool_name() {
        ToolResult r = tool.execute(new ToolCall("something_else", Map.of("action", "list")));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("Unknown tool"));
    }

    // ── action=add ──

    @Test
    @DisplayName("add 完整 → 成功 + 落盘 + registry 里有")
    void add_success() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("action", "add");
        args.put("name", "postgres");
        args.put("command", "npx");
        args.put("args", List.of("-y", "@modelcontextprotocol/server-postgres"));
        args.put("env", Map.of("DATABASE_URL", "postgres://localhost/x"));

        ToolResult r = call(args);
        assertTrue(r.isSuccess(), r.getOutput());
        assertTrue(r.getOutput().contains("connect_mcp"),
                "add 成功输出应引导 LLM 调 connect_mcp");
        assertTrue(registry.contains("postgres"));
        assertTrue(Files.exists(store.getDir().resolve("postgres.json")));

        McpServerRecord got = registry.get("postgres").orElseThrow();
        assertEquals("npx", got.command());
        assertEquals(List.of("-y", "@modelcontextprotocol/server-postgres"), got.args());
        assertEquals(Map.of("DATABASE_URL", "postgres://localhost/x"), got.env());
    }

    @Test
    @DisplayName("add 不传 env → env 默认空 map")
    void add_without_env() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("action", "add");
        args.put("name", "simple");
        args.put("command", "node");
        args.put("args", List.of("server.js"));

        ToolResult r = call(args);
        assertTrue(r.isSuccess(), r.getOutput());
        assertEquals(Map.of(), registry.get("simple").orElseThrow().env());
    }

    @Test
    @DisplayName("add 重名 → 报 'already exists' 并引导 remove")
    void add_duplicate() throws IOException {
        registry.add(mkRecord("filesystem"));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("action", "add");
        args.put("name", "filesystem");
        args.put("command", "npx");
        args.put("args", List.of("-y", "server-x"));

        ToolResult r = call(args);
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("already exists"));
        assertTrue(r.getOutput().contains("remove"));
    }

    @Test
    @DisplayName("add 缺 name / command / args → 各报友好错")
    void add_missing_required_fields() {
        ToolResult r1 = call(Map.of("action", "add", "command", "npx", "args", List.of()));
        assertFalse(r1.isSuccess());
        assertTrue(r1.getOutput().contains("name"));

        ToolResult r2 = call(Map.of("action", "add", "name", "x", "args", List.of()));
        assertFalse(r2.isSuccess());
        assertTrue(r2.getOutput().contains("command"));

        ToolResult r3 = call(Map.of("action", "add", "name", "x", "command", "npx"));
        assertFalse(r3.isSuccess());
        assertTrue(r3.getOutput().contains("args"));
    }

    @Test
    @DisplayName("add name 含非法字符 → registry.add 上抛 IAE,tool 转成友好错")
    void add_illegal_name() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("action", "add");
        args.put("name", "a/b");
        args.put("command", "npx");
        args.put("args", List.of());
        ToolResult r = call(args);
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().toLowerCase().contains("failed to add"));
        assertFalse(registry.contains("a/b"), "非法 name 不应留在 registry");
    }

    @Test
    @DisplayName("add args 非 array → 报明确类型错误")
    void add_args_wrong_type() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("action", "add");
        args.put("name", "x");
        args.put("command", "npx");
        args.put("args", "not-a-list");   // 应该是 List

        ToolResult r = call(args);
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("array of strings"));
    }

    // ── action=list ──

    @Test
    @DisplayName("list 空 → (no MCP servers registered)")
    void list_empty() {
        ToolResult r = call(Map.of("action", "list"));
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("(no MCP servers"));
    }

    @Test
    @DisplayName("list 多个 → 每行含 name + status")
    void list_multiple() throws IOException {
        registry.add(mkRecord("filesystem"));
        registry.add(mkRecord("git"));

        ToolResult r = call(Map.of("action", "list"));
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("filesystem"));
        assertTrue(r.getOutput().contains("git"));
        assertTrue(r.getOutput().contains("NEVER_CONNECTED"));
    }

    @Test
    @DisplayName("list FAILED server 输出应含 lastError")
    void list_shows_error() throws IOException {
        registry.add(mkRecord("filesystem"));
        registry.markFailed("filesystem", "connection refused");

        ToolResult r = call(Map.of("action", "list"));
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("FAILED"));
        assertTrue(r.getOutput().contains("connection refused"));
    }

    // ── action=view ──

    @Test
    @DisplayName("view 存在 → 返完整配置")
    void view_existing() throws IOException {
        registry.add(mkRecord("filesystem"));
        ToolResult r = call(Map.of("action", "view", "name", "filesystem"));
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("command: npx"));
        assertTrue(r.getOutput().contains("addedAt:"));
    }

    @Test
    @DisplayName("view 不存在 → 友好错 + 列出 available")
    void view_missing() throws IOException {
        registry.add(mkRecord("filesystem"));
        ToolResult r = call(Map.of("action", "view", "name", "ghost"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("not found"));
        assertTrue(r.getOutput().contains("filesystem"));
    }

    @Test
    @DisplayName("view 缺 name → 报错")
    void view_missing_name() {
        ToolResult r = call(Map.of("action", "view"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("name"));
    }

    // ── action=remove ──

    @Test
    @DisplayName("remove 存在 → 磁盘 + registry 都删")
    void remove_existing() throws IOException {
        registry.add(mkRecord("filesystem"));
        assertTrue(Files.exists(store.getDir().resolve("filesystem.json")));

        ToolResult r = call(Map.of("action", "remove", "name", "filesystem"));
        assertTrue(r.isSuccess());
        assertFalse(registry.contains("filesystem"));
        assertFalse(Files.exists(store.getDir().resolve("filesystem.json")));
    }

    @Test
    @DisplayName("remove 不存在 → 友好错")
    void remove_missing() {
        ToolResult r = call(Map.of("action", "remove", "name", "ghost"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("not found"));
    }

    @Test
    @DisplayName("remove 缺 name → 报错")
    void remove_missing_name() {
        ToolResult r = call(Map.of("action", "remove"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("name"));
    }

    // ── Tool 定义 ──

    @Test
    @DisplayName("getTools 返 1 个定义,name = mcp_manage")
    void tool_definition() {
        var defs = tool.getTools();
        assertEquals(1, defs.size());
        assertEquals("mcp_manage", defs.get(0).getName());
    }

    private static McpServerRecord mkRecord(String name) {
        return new McpServerRecord(
                name, "npx", List.of("-y", "some-server"), Map.of(),
                true, McpServerRecord.Status.NEVER_CONNECTED, null,
                java.time.Instant.now(), null);
    }
}
