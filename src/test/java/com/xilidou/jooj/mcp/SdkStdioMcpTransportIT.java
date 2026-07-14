package com.xilidou.jooj.mcp;

import com.xilidou.jooj.config.JsonMappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真接 npx 子进程的 MCP integration test。
 *
 * <p><b>默认不跑</b> —— surefire 配置 {@code excludedGroups=integration}
 * (mvn test 不触发);手工跑用 {@code mvn test -Dgroups=integration}。
 *
 * <p>需要本机:
 * <ul>
 *   <li>node + npx 安装</li>
 *   <li>能访问 npm registry(第一次会拉 {@code @modelcontextprotocol/server-everything})</li>
 *   <li>cold-start 可能耗 30s+(npm 下包),已配 startupTimeoutMs=60s</li>
 * </ul>
 *
 * <p>验证完整链路:
 * <ol>
 *   <li>SdkStdioMcpTransport 启动 server-everything 子进程</li>
 *   <li>initialize → 协议握手</li>
 *   <li>listTools 返回 13 个工具(echo / get-sum / 等等)</li>
 *   <li>callTool('echo', {message:'hello'}) → "Echo: hello"</li>
 * </ol>
 */
@Tag("integration")
class SdkStdioMcpTransportIT {

    private SdkStdioMcpTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        McpProperties props = new McpProperties();
        McpProperties.Server everything = new McpProperties.Server();
        everything.setCommand("npx");
        everything.setArgs(List.of("-y", "@modelcontextprotocol/server-everything"));
        props.getServers().put("everything", everything);
        props.setStartupTimeoutMs(60_000);

        // M1 (2026-07-14):走 McpServerRegistry 而非 McpProperties.servers
        McpServersJsonStore store = new McpServersJsonStore(JsonMappers.newMapper());
        cleanDir(store.getDir());
        McpServerRegistry serverRegistry = new McpServerRegistry(props, store);

        // mock fallback 设 null —— 这次只测真 SDK,不要 mock 介入
        transport = new SdkStdioMcpTransport(serverRegistry, new EmptyObjectProvider<>());
    }

    private static void cleanDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) Files.deleteIfExists(p);
        }
    }

    @AfterEach
    void tearDown() {
        if (transport != null) transport.shutdown();
    }

    @Test
    @DisplayName("listTools('everything') 返 13 个工具,含 echo / get-sum")
    void list_tools_real_server() {
        List<McpToolDef> tools = transport.listTools("everything");
        assertNotNull(tools);
        assertTrue(tools.size() >= 10,
                "everything server 应至少暴露 10 个工具,实际:" + tools.size());

        List<String> names = tools.stream().map(McpToolDef::getName).toList();
        assertTrue(names.contains("echo"), "应有 echo,实际:" + names);
        assertTrue(names.contains("get-sum") || names.contains("add"),
                "应有 get-sum 或 add,实际:" + names);
    }

    @Test
    @DisplayName("callTool('echo', {message:'hello jooj'}) → 'Echo: hello jooj'")
    void call_echo_real_server() {
        // 先 listTools 触发启动
        transport.listTools("everything");

        String result = transport.callTool("everything", "echo",
                Map.of("message", "hello jooj"));
        assertNotNull(result);
        assertTrue(result.contains("hello jooj"),
                "echo 应回显输入,实际:" + result);
    }

    /** 简单 ObjectProvider 实现,getIfAvailable 返 null。 */
    private static class EmptyObjectProvider<T>
            implements org.springframework.beans.factory.ObjectProvider<T> {
        @Override public T getObject() { throw new UnsupportedOperationException(); }
        @Override public T getObject(Object... args) { throw new UnsupportedOperationException(); }
        @Override public T getIfAvailable() { return null; }
        @Override public T getIfUnique() { return null; }
    }
}
