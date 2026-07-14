package com.xilidou.jooj.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojTestConfig;
import com.xilidou.jooj.mcp.McpServerRecord;
import com.xilidou.jooj.mcp.McpServerRegistry;
import com.xilidou.jooj.mcp.McpServersJsonStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 锁定 {@link SidebarController} MCP 相关 5 个 endpoint 的契约(M4)。
 *
 * <p>surefire 会注入 {@code JOOJ_HOME=target/.jooj-test},本测试的 mcp-servers 落盘在
 * {@code target/.jooj-test/mcp-servers/}。{@code @BeforeEach} 清空目录 + rescan 保证隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(JoojTestConfig.class)
@ActiveProfiles({"test", "web"})
class SidebarControllerMcpTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired McpServerRegistry mcpRegistry;
    @Autowired McpServersJsonStore mcpStore;

    @BeforeEach
    void cleanUp() throws IOException {
        // 每个测试跑之前清干净 mcp-servers 目录 + registry
        Path dir = mcpStore.getDir();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path p : stream) Files.deleteIfExists(p);
            }
        }
        mcpRegistry.rescan(true);
    }

    // ── GET /mcp/servers ──

    @Test
    @DisplayName("GET /mcp/servers 空 → total=0, servers=[]")
    void list_empty() throws Exception {
        mvc.perform(get("/api/mcp/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.servers").isArray())
                .andExpect(jsonPath("$.servers").isEmpty());
    }

    @Test
    @DisplayName("GET /mcp/servers 有 2 个 → total=2 + summary 各字段就位")
    void list_two() throws Exception {
        mcpRegistry.add(mkRecord("filesystem"));
        mcpRegistry.add(mkRecord("git"));

        mvc.perform(get("/api/mcp/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.servers[?(@.name == 'filesystem')]").exists())
                .andExpect(jsonPath("$.servers[?(@.name == 'git')]").exists())
                .andExpect(jsonPath("$.servers[0].command").value("npx"))
                .andExpect(jsonPath("$.servers[0].status").value("NEVER_CONNECTED"))
                .andExpect(jsonPath("$.servers[0].enabled").value(true))
                .andExpect(jsonPath("$.servers[0].addedAt").exists());
    }

    // ── POST /mcp/servers ──

    @Test
    @DisplayName("POST /mcp/servers 合法请求 → 200 + summary + registry 里有")
    void add_success() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "name", "postgres",
                "command", "npx",
                "args", List.of("-y", "@modelcontextprotocol/server-postgres"),
                "env", Map.of("DATABASE_URL", "postgres://localhost/x")
        ));
        mvc.perform(post("/api/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("postgres"))
                .andExpect(jsonPath("$.status").value("NEVER_CONNECTED"))
                .andExpect(jsonPath("$.command").value("npx"));

        // registry 里真的有
        var got = mcpRegistry.get("postgres").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("npx", got.command());
    }

    @Test
    @DisplayName("POST /mcp/servers 缺 name → 400 + error body")
    void add_missing_name() throws Exception {
        mvc.perform(post("/api/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("command", "npx"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /mcp/servers 缺 command → 400")
    void add_missing_command() throws Exception {
        mvc.perform(post("/api/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "x"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /mcp/servers 重名 → 400 + error 提示 already exists")
    void add_duplicate() throws Exception {
        mcpRegistry.add(mkRecord("filesystem"));

        mvc.perform(post("/api/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "filesystem",
                                "command", "different-cmd"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    @DisplayName("POST /mcp/servers name 含非法字符 → 400")
    void add_illegal_name() throws Exception {
        mvc.perform(post("/api/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "a/b",
                                "command", "npx"))))
                .andExpect(status().isBadRequest());
    }

    // ── DELETE /mcp/servers/{name} ──

    @Test
    @DisplayName("DELETE 存在 → 200 removed=true + 磁盘 + registry 都删")
    void delete_existing() throws Exception {
        mcpRegistry.add(mkRecord("filesystem"));

        mvc.perform(delete("/api/mcp/servers/filesystem"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(true))
                .andExpect(jsonPath("$.name").value("filesystem"));

        org.junit.jupiter.api.Assertions.assertFalse(mcpRegistry.contains("filesystem"));
    }

    @Test
    @DisplayName("DELETE 不存在 → 200 removed=false + reason")
    void delete_missing() throws Exception {
        mvc.perform(delete("/api/mcp/servers/ghost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(false))
                .andExpect(jsonPath("$.reason").exists());
    }

    // ── POST /mcp/servers/{name}/enable ──

    @Test
    @DisplayName("POST /enable 禁用存在的 → status=DISABLED + enabled=false")
    void set_enabled_disable() throws Exception {
        mcpRegistry.add(mkRecord("filesystem"));

        mvc.perform(post("/api/mcp/servers/filesystem/enable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    @DisplayName("POST /enable 启用已禁用的 → status=NEVER_CONNECTED")
    void set_enabled_reenable() throws Exception {
        mcpRegistry.add(mkRecord("filesystem"));
        mcpRegistry.setEnabled("filesystem", false);

        mvc.perform(post("/api/mcp/servers/filesystem/enable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.status").value("NEVER_CONNECTED"));
    }

    @Test
    @DisplayName("POST /enable 不存在的 name → 400")
    void set_enabled_missing() throws Exception {
        mvc.perform(post("/api/mcp/servers/ghost/enable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isBadRequest());
    }

    // ── POST /mcp/rescan ──

    @Test
    @DisplayName("POST /mcp/rescan 强制重扫 → 返最新完整列表")
    void rescan_returns_current() throws Exception {
        mcpRegistry.add(mkRecord("filesystem"));

        mvc.perform(post("/api/mcp/rescan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.servers[0].name").value("filesystem"));
    }

    private static McpServerRecord mkRecord(String name) {
        return new McpServerRecord(
                name, "npx", List.of("-y", "some-server"), Map.of(),
                true, McpServerRecord.Status.NEVER_CONNECTED, null,
                Instant.now(), null);
    }
}
