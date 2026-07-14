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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link McpServersJsonStore} 的读写行为。
 *
 * <p>surefire 会注入 {@code JOOJ_HOME=target/.jooj-test},所以本测试跑在
 * {@code target/.jooj-test/mcp-servers/} 目录下,不污染真 {@code ~/.jooj/}。
 * 每次 @BeforeEach 清空目录,保证测试彼此隔离。
 */
class McpServersJsonStoreTest {

    private McpServersJsonStore store;

    @BeforeEach
    void setUp() throws IOException {
        ObjectMapper mapper = JsonMappers.newMapper();
        store = new McpServersJsonStore(mapper);
        // 清空 target/.jooj-test/mcp-servers/*.json —— 保证测试隔离
        cleanDir(store.getDir());
    }

    private static void cleanDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) Files.deleteIfExists(p);
        }
    }

    // ── save / loadAll ─────────────────────────────────

    @Test
    @DisplayName("save 一个 record 后,loadAll 能读回来")
    void save_and_load() throws IOException {
        McpServerRecord fs = new McpServerRecord(
                "filesystem", "npx",
                List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"),
                Map.of("LOG_LEVEL", "info"),
                true, McpServerRecord.Status.NEVER_CONNECTED,
                null, Instant.parse("2026-01-01T00:00:00Z"), null);

        store.save(fs);
        List<McpServerRecord> loaded = store.loadAll();

        assertEquals(1, loaded.size());
        McpServerRecord got = loaded.get(0);
        assertEquals("filesystem", got.name());
        assertEquals("npx", got.command());
        assertEquals(List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"), got.args());
        assertEquals(Map.of("LOG_LEVEL", "info"), got.env());
        assertTrue(got.enabled());
        assertEquals(McpServerRecord.Status.NEVER_CONNECTED, got.status());
    }

    @Test
    @DisplayName("loadAll 空目录 → 返空列表")
    void loadAll_empty() {
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    @DisplayName("save 覆盖同名文件 → 后写的覆盖前写的")
    void save_overwrites() throws IOException {
        McpServerRecord v1 = mkRecord("git", McpServerRecord.Status.NEVER_CONNECTED, null);
        McpServerRecord v2 = mkRecord("git", McpServerRecord.Status.CONNECTED, null);

        store.save(v1);
        store.save(v2);

        List<McpServerRecord> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        assertEquals(McpServerRecord.Status.CONNECTED, loaded.get(0).status());
    }

    @Test
    @DisplayName("save 多个不同 name → 每个一个文件,loadAll 全部读回")
    void save_multiple() throws IOException {
        store.save(mkRecord("filesystem", McpServerRecord.Status.NEVER_CONNECTED, null));
        store.save(mkRecord("git", McpServerRecord.Status.CONNECTED, null));
        store.save(mkRecord("postgres", McpServerRecord.Status.FAILED, "conn refused"));

        List<McpServerRecord> loaded = store.loadAll();
        assertEquals(3, loaded.size());
    }

    // ── delete ─────────────────────────────────────────

    @Test
    @DisplayName("delete 存在的 → 磁盘文件消失,loadAll 空")
    void delete_existing() throws IOException {
        store.save(mkRecord("filesystem", McpServerRecord.Status.NEVER_CONNECTED, null));
        store.delete("filesystem");
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    @DisplayName("delete 不存在的 → 静默,不抛")
    void delete_missing_is_noop() {
        assertDoesNotThrow(() -> store.delete("does-not-exist"));
    }

    // ── validateName ─────────────────────────────────

    @Test
    @DisplayName("save name 含 '/' 或 '..' 或空白 → 抛 IAE")
    void save_rejects_illegal_names() {
        assertThrows(IllegalArgumentException.class,
                () -> store.save(mkRecord("a/b", McpServerRecord.Status.NEVER_CONNECTED, null)));
        assertThrows(IllegalArgumentException.class,
                () -> store.save(mkRecord("../evil", McpServerRecord.Status.NEVER_CONNECTED, null)));
        assertThrows(IllegalArgumentException.class,
                () -> store.save(mkRecord("", McpServerRecord.Status.NEVER_CONNECTED, null)));
    }

    @Test
    @DisplayName("delete name 含 '/' → 抛 IAE")
    void delete_rejects_illegal_names() {
        assertThrows(IllegalArgumentException.class,
                () -> store.delete("a/b"));
    }

    @Test
    @DisplayName("save name 含合法字符(字母数字 _ -)→ OK")
    void save_accepts_legal_names() throws IOException {
        assertDoesNotThrow(
                () -> store.save(mkRecord("my_svc-v2", McpServerRecord.Status.NEVER_CONNECTED, null)));
    }

    // ── 损坏文件容错 ───────────────────────────────

    @Test
    @DisplayName("loadAll 遇到无法解析的 JSON 文件 → log.warn 跳过,不阻断其他文件")
    void loadAll_survives_corrupted_file() throws IOException {
        // 手动写一个坏的 JSON 文件
        Files.writeString(store.getDir().resolve("corrupted.json"), "{ not: valid json ");
        // 再存一个好的
        store.save(mkRecord("good", McpServerRecord.Status.NEVER_CONNECTED, null));

        List<McpServerRecord> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("good", loaded.get(0).name());
    }

    private static McpServerRecord mkRecord(String name,
                                            McpServerRecord.Status status,
                                            String error) {
        return new McpServerRecord(
                name, "npx", List.of("-y", "some-server"), Map.of(),
                true, status, error,
                Instant.parse("2026-01-01T00:00:00Z"), null);
    }
}
