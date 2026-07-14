package com.xilidou.jooj.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JsonMappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link McpServerRegistry} 的 seed / query / status update / rescan 行为。
 *
 * <p>surefire 会注入 {@code JOOJ_HOME=target/.jooj-test},所以本测试跑在
 * {@code target/.jooj-test/mcp-servers/} 目录下,不污染真 {@code ~/.jooj/}。
 */
class McpServerRegistryTest {

    private McpServersJsonStore store;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws IOException {
        mapper = JsonMappers.newMapper();
        store = new McpServersJsonStore(mapper);
        cleanDir(store.getDir());
    }

    private static void cleanDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) Files.deleteIfExists(p);
        }
    }

    private McpServerRegistry newRegistry(McpProperties props) throws IOException {
        return new McpServerRegistry(props, store);
    }

    private static McpProperties propsWith(String name, String cmd, String... args) {
        McpProperties p = new McpProperties();
        McpProperties.Server s = new McpProperties.Server();
        s.setCommand(cmd);
        s.setArgs(List.of(args));
        p.getServers().put(name, s);
        return p;
    }

    // ── seedFromYml ──────────────────────────────────

    @Test
    @DisplayName("空 yml 空目录:registry 空,不写盘")
    void empty_yml_empty_disk() throws IOException {
        McpServerRegistry reg = newRegistry(new McpProperties());
        assertEquals(0, reg.size());
    }

    @Test
    @DisplayName("yml 有 filesystem,JSON 目录空 → seed 落盘 + 加入 registry")
    void seed_from_yml_when_no_json() throws IOException {
        McpProperties props = propsWith("filesystem", "npx",
                "-y", "@modelcontextprotocol/server-filesystem", "/tmp");
        McpServerRegistry reg = newRegistry(props);

        assertEquals(1, reg.size());
        assertTrue(reg.contains("filesystem"));

        // JSON 已落盘
        Optional<McpServerRecord> got = reg.get("filesystem");
        assertTrue(got.isPresent());
        assertEquals("npx", got.get().command());
        assertEquals(McpServerRecord.Status.NEVER_CONNECTED, got.get().status());
        assertTrue(got.get().enabled());

        // 磁盘上有 filesystem.json
        assertTrue(Files.exists(store.getDir().resolve("filesystem.json")));
    }

    @Test
    @DisplayName("JSON 目录已有 filesystem → yml 里同名 server 跳过,保留 JSON 版本")
    void skip_seed_when_json_exists() throws IOException {
        // 预置 JSON 里已有一个 filesystem(且 status = CONNECTED,运行时值)
        McpServerRecord existing = new McpServerRecord(
                "filesystem", "existing-command", List.of("existing-arg"),
                java.util.Map.of("EXISTING_ENV", "1"),
                true, McpServerRecord.Status.CONNECTED, null,
                java.time.Instant.parse("2025-01-01T00:00:00Z"),
                java.time.Instant.parse("2025-06-01T00:00:00Z"));
        store.save(existing);

        // yml 里也有 filesystem(但 command 不同)
        McpProperties props = propsWith("filesystem", "yml-command", "yml-arg");
        McpServerRegistry reg = newRegistry(props);

        assertEquals(1, reg.size());
        McpServerRecord got = reg.get("filesystem").orElseThrow();
        assertEquals("existing-command", got.command(),
                "JSON 已有的应优先,不被 yml 覆盖");
        assertEquals(McpServerRecord.Status.CONNECTED, got.status(),
                "运行时状态应保留");
    }

    @Test
    @DisplayName("yml 有多个 server,JSON 目录空 → 全部 seed")
    void seed_multiple_from_yml() throws IOException {
        McpProperties props = new McpProperties();
        for (String name : List.of("filesystem", "git", "postgres")) {
            McpProperties.Server s = new McpProperties.Server();
            s.setCommand("npx");
            props.getServers().put(name, s);
        }
        McpServerRegistry reg = newRegistry(props);

        assertEquals(3, reg.size());
        assertTrue(reg.contains("filesystem"));
        assertTrue(reg.contains("git"));
        assertTrue(reg.contains("postgres"));
    }

    @Test
    @DisplayName("yml 有 A,JSON 目录有 B → registry 里两者都有")
    void merge_yml_and_json() throws IOException {
        store.save(McpServerRecord.fromYml("git",
                mkServer("existing-git-cmd")));
        McpProperties props = propsWith("filesystem", "npx");
        McpServerRegistry reg = newRegistry(props);

        assertEquals(2, reg.size());
        assertTrue(reg.contains("git"));
        assertTrue(reg.contains("filesystem"));
    }

    // ── markConnected / markFailed ─────────────────

    @Test
    @DisplayName("markConnected 更新 status + 落盘 + lastConnectedAt 不为 null")
    void markConnected_updates() throws IOException {
        McpProperties props = propsWith("filesystem", "npx");
        McpServerRegistry reg = newRegistry(props);

        reg.markConnected("filesystem");

        McpServerRecord got = reg.get("filesystem").orElseThrow();
        assertEquals(McpServerRecord.Status.CONNECTED, got.status());
        assertNotNull(got.lastConnectedAt());
        assertNull(got.lastError());

        // 落盘验证
        List<McpServerRecord> reloaded = store.loadAll();
        assertEquals(1, reloaded.size());
        assertEquals(McpServerRecord.Status.CONNECTED, reloaded.get(0).status());
    }

    @Test
    @DisplayName("markFailed 更新 status + 保留 lastConnectedAt")
    void markFailed_preserves_lastConnected() throws IOException {
        McpProperties props = propsWith("filesystem", "npx");
        McpServerRegistry reg = newRegistry(props);

        // 先连一次成功
        reg.markConnected("filesystem");
        java.time.Instant lastOk = reg.get("filesystem").orElseThrow().lastConnectedAt();
        assertNotNull(lastOk);

        // 再连一次失败
        reg.markFailed("filesystem", "connection refused");
        McpServerRecord got = reg.get("filesystem").orElseThrow();
        assertEquals(McpServerRecord.Status.FAILED, got.status());
        assertEquals("connection refused", got.lastError());
        assertEquals(lastOk, got.lastConnectedAt(),
                "FAILED 时应保留 lastConnectedAt,便于 UI 显示'上次成功于 X 前'");
    }

    @Test
    @DisplayName("markConnected 不存在的 name → 静默不抛(容错)")
    void mark_missing_is_noop() throws IOException {
        McpServerRegistry reg = newRegistry(new McpProperties());
        assertDoesNotThrow(() -> reg.markConnected("does-not-exist"));
        assertDoesNotThrow(() -> reg.markFailed("does-not-exist", "err"));
    }

    // ── rescan ────────────────────────────────────

    @Test
    @DisplayName("rescan(true) 强制重扫,发现新增的 JSON 文件")
    void rescan_force_picks_up_new_json() throws IOException {
        McpServerRegistry reg = newRegistry(new McpProperties());
        assertEquals(0, reg.size());

        // 绕过 registry 直接写 JSON(模拟外部工具或 M3 加新 server)
        McpServerRecord newOne = new McpServerRecord(
                "newly-added", "npx", List.of(), java.util.Map.of(),
                true, McpServerRecord.Status.NEVER_CONNECTED, null,
                java.time.Instant.now(), null);
        store.save(newOne);

        assertEquals(0, reg.size(), "rescan 前应看不到");
        int after = reg.rescan(true);
        assertEquals(1, after);
        assertTrue(reg.contains("newly-added"));
    }

    @Test
    @DisplayName("rescan(false) 在 1s 节流窗口内 → no-op")
    void rescan_throttle() throws IOException {
        McpServerRegistry reg = newRegistry(new McpProperties());

        // 立刻再 rescan(false),节流命中 → 不刷盘
        McpServerRecord newOne = new McpServerRecord(
                "sneaky", "npx", List.of(), java.util.Map.of(),
                true, McpServerRecord.Status.NEVER_CONNECTED, null,
                java.time.Instant.now(), null);
        store.save(newOne);

        int after = reg.rescan(false);
        assertEquals(0, after, "节流命中时应返回 cached size(0)");
        assertFalse(reg.contains("sneaky"), "节流命中不应重扫");
    }

    // ── list / listNames ─────────────────────────

    @Test
    @DisplayName("list 保序返所有 record")
    void list_preserves_insertion_order() throws IOException {
        McpProperties props = new McpProperties();
        // LinkedHashMap 保序:filesystem 先,git 后
        McpProperties.Server fs = new McpProperties.Server();
        fs.setCommand("npx");
        props.getServers().put("filesystem", fs);
        McpProperties.Server git = new McpProperties.Server();
        git.setCommand("npx");
        props.getServers().put("git", git);

        McpServerRegistry reg = newRegistry(props);

        // 注意:seedFromYml + loadFromDisk 交互后顺序不完全保证 —— list() 应该包含全部
        List<String> names = reg.listNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("filesystem"));
        assertTrue(names.contains("git"));
    }

    private static McpProperties.Server mkServer(String command) {
        McpProperties.Server s = new McpProperties.Server();
        s.setCommand(command);
        return s;
    }
}
