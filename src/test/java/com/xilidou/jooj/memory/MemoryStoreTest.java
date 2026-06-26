package com.xilidou.jooj.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link MemoryStore} 的核心行为。
 *
 * <p>11 个关键场景:
 * <ol>
 *   <li>write + read roundtrip(frontmatter 不丢字段)</li>
 *   <li>同名(slug 相同)write 覆盖旧文件</li>
 *   <li>name 含空格 → slug 转 -</li>
 *   <li>name 含非法字符 → slug 转 _,文件落在 memoryDir 内</li>
 *   <li>delete 删除文件 + 重建索引</li>
 *   <li>list 按 mtime 倒序</li>
 *   <li>list 跳过索引文件本身</li>
 *   <li>rebuildIndex:多个文件按 name 字典序写索引</li>
 *   <li>readIndex:无文件时返回空字符串</li>
 *   <li>read 不存在的文件 → empty</li>
 *   <li>read 用恶意 filename(含 .. /)→ 抛异常</li>
 * </ol>
 */
class MemoryStoreTest {

    private static MemoryConfig configForDir(Path dir) {
        return new MemoryConfig(dir, "MEMORY.md", 4096, 10);
    }

    private static MemoryFile sample(String name, String desc, String body) {
        return MemoryFile.of(name, MemoryFile.Type.USER, desc, body);
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 1:write + read roundtrip
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("write + read should preserve all frontmatter fields")
    void write_read_roundtrip(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(configForDir(tempDir));
        MemoryFile original = MemoryFile.of(
                "user-preference-tabs",
                MemoryFile.Type.USER,
                "User prefers tabs for indentation",
                "Always use tabs when writing or editing files.");

        Path written = store.write(original);

        assertTrue(Files.exists(written));
        assertEquals("user-preference-tabs.md", original.getFilename(),
                "filename 应被回填");

        Optional<MemoryFile> read = store.read("user-preference-tabs.md");
        assertTrue(read.isPresent());
        MemoryFile r = read.get();
        assertEquals("user-preference-tabs", r.getName());
        assertEquals(MemoryFile.Type.USER, r.getType());
        assertEquals("User prefers tabs for indentation", r.getDescription());
        assertTrue(r.getBody().contains("Always use tabs"));
        assertEquals("user-preference-tabs.md", r.getFilename());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 2:同名 write 覆盖
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("write with same name should overwrite existing file")
    void write_same_name_overwrites(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(configForDir(tempDir));
        store.write(sample("foo", "first description", "first body"));
        store.write(sample("foo", "second description", "second body"));

        List<MemoryFile> all = store.list();
        assertEquals(1, all.size(), "同名两次应该只剩 1 个");
        assertEquals("second description", all.get(0).getDescription());
        assertTrue(all.get(0).getBody().contains("second body"));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 3:name 空格转 -
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("name with spaces should be slug-ified to dashes")
    void name_with_spaces_slugged_to_dashes(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(configForDir(tempDir));
        MemoryFile m = sample("User Preference Tabs", "desc", "body");
        store.write(m);

        assertEquals("user-preference-tabs.md", m.getFilename());
        assertTrue(Files.exists(tempDir.resolve("user-preference-tabs.md")));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 4:name 含非法字符 → 安全清洗,文件落在 memoryDir 内
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("name with path-traversal chars should be sanitized; file stays in memoryDir")
    void path_traversal_name_sanitized(@TempDir Path tempDir) throws IOException {
        MemoryStore store = new MemoryStore(configForDir(tempDir));
        // 恶意 name:试图跳出 memoryDir
        MemoryFile m = sample("../../etc/passwd", "evil", "body");
        store.write(m);

        // 文件应落在 tempDir 里,不可能含 ..
        try (var stream = Files.walk(tempDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                assertTrue(p.startsWith(tempDir),
                        "file should stay in memoryDir: " + p);
                String name = p.getFileName().toString();
                assertFalse(name.contains(".."), "filename must not contain ..");
                assertFalse(name.contains("/"), "filename must not contain /");
            });
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 5:delete + 索引同步重建
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete should remove file and rebuild index")
    void delete_rebuilds_index(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(configForDir(tempDir));
        store.write(sample("foo", "foo desc", "foo body"));
        store.write(sample("bar", "bar desc", "bar body"));

        // 删 foo
        boolean deleted = store.delete("foo.md");
        assertTrue(deleted);
        assertFalse(Files.exists(tempDir.resolve("foo.md")));

        // 索引里只剩 bar
        String idx = store.readIndex();
        assertFalse(idx.contains("foo"), "index should NOT contain deleted foo");
        assertTrue(idx.contains("bar"), "index should still contain bar");

        // 删不存在的文件:返回 false,不抛异常
        assertFalse(store.delete("nonexistent.md"));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 6:list 按 mtime 倒序
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list should return newest-first by mtime")
    void list_orders_by_mtime_desc(@TempDir Path tempDir) throws IOException, InterruptedException {
        MemoryStore store = new MemoryStore(configForDir(tempDir));
        store.write(sample("first", "first desc", "first body"));
        // 强制设置 mtime,避免文件系统时间精度太低
        Path firstFile = tempDir.resolve("first.md");
        Files.setLastModifiedTime(firstFile,
                java.nio.file.attribute.FileTime.fromMillis(1000L));

        store.write(sample("second", "second desc", "second body"));
        Path secondFile = tempDir.resolve("second.md");
        Files.setLastModifiedTime(secondFile,
                java.nio.file.attribute.FileTime.fromMillis(2000L));

        List<MemoryFile> all = store.list();
        assertEquals(2, all.size());
        assertEquals("second", all.get(0).getName(), "新的应该排第一");
        assertEquals("first", all.get(1).getName());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 7:list 跳过索引文件本身
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list should skip the index file itself")
    void list_skips_index_file(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(configForDir(tempDir));
        store.write(sample("foo", "desc", "body"));

        // MEMORY.md 应该存在(rebuildIndex 写的)
        assertTrue(Files.exists(tempDir.resolve("MEMORY.md")));

        List<MemoryFile> all = store.list();
        assertEquals(1, all.size(), "list 不应包含 MEMORY.md");
        assertEquals("foo", all.get(0).getName());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 8:rebuildIndex 内容格式 — 按 name 字典序
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rebuildIndex should emit one line per memory in name-sorted order")
    void rebuild_index_format(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(configForDir(tempDir));
        store.write(sample("zoo", "zoo desc", "zoo body"));
        store.write(sample("alpha", "alpha desc", "alpha body"));
        store.write(sample("middle", "middle desc", "middle body"));

        String idx = store.readIndex();
        // 按字典序排,每行 - [name](filename) — desc
        String[] lines = idx.split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].startsWith("- [alpha]("));
        assertTrue(lines[1].startsWith("- [middle]("));
        assertTrue(lines[2].startsWith("- [zoo]("));
        assertTrue(lines[0].contains("alpha desc"));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 9:readIndex 空目录返回空字符串
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("readIndex should return empty string when no memories exist")
    void read_index_empty_dir(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(configForDir(tempDir));
        assertEquals("", store.readIndex(),
                "无文件时索引应为空字符串(不该抛异常)");
        assertTrue(store.list().isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 10:read 不存在文件 → empty
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("read of non-existent file should return Optional.empty()")
    void read_missing_returns_empty(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(configForDir(tempDir));
        assertTrue(store.read("ghost.md").isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 11:read 用恶意 filename 抛异常
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("read with path-traversal filename should throw")
    void read_path_traversal_filename_throws(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(configForDir(tempDir));
        assertThrows(IllegalArgumentException.class,
                () -> store.read("../passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> store.read("sub/dir/file.md"));
        assertThrows(IllegalArgumentException.class,
                () -> store.read("a\\b.md"));
        assertThrows(IllegalArgumentException.class,
                () -> store.read(""));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 12:body 超长截断
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("write with body > maxBodyBytes should truncate + append ...")
    void write_truncates_long_body(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(new MemoryConfig(tempDir, "MEMORY.md", 100, 10));
        StringBuilder big = new StringBuilder();
        while (big.length() < 500) big.append("xxxx ");
        store.write(sample("big", "big desc", big.toString()));

        Optional<MemoryFile> read = store.read("big.md");
        assertTrue(read.isPresent());
        // body 应被截断 + 含 "..." 标记(可能有结尾换行)
        String body = read.get().getBody();
        assertTrue(body.contains("..."), "long body should contain truncation marker");
        // 实际长度可能略大 100(因为 "..." 加在后面 + 可能的尾换行)
        assertTrue(body.length() <= 110, "body should be near maxBodyBytes(100) + '...': " + body.length());
        // 原文 500 字符,远大于 100 — 不该完整保留
        assertTrue(body.length() < 500, "body should be truncated, not full original");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 13:解析无 frontmatter 的文件不抛异常
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("parse should tolerate file with no frontmatter")
    void parse_tolerates_missing_frontmatter(@TempDir Path tempDir) throws IOException {
        // 手写一个没有 frontmatter 的 .md 文件
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("orphan.md"), "just plain text\n", StandardCharsets.UTF_8);

        MemoryStore store = new MemoryStore(configForDir(tempDir));
        Optional<MemoryFile> read = store.read("orphan.md");
        assertTrue(read.isPresent());
        // 没有 frontmatter:body 是整段,type 默认 USER
        assertEquals(MemoryFile.Type.USER, read.get().getType());
        assertTrue(read.get().getBody().contains("just plain text"));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 14:Type.parse 容错
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Type.parse should tolerate unknown values and case")
    void type_parse_is_tolerant() {
        assertEquals(MemoryFile.Type.USER, MemoryFile.Type.parse("user"));
        assertEquals(MemoryFile.Type.USER, MemoryFile.Type.parse("USER"));
        assertEquals(MemoryFile.Type.FEEDBACK, MemoryFile.Type.parse("Feedback"));
        assertEquals(MemoryFile.Type.PROJECT, MemoryFile.Type.parse("project"));
        assertEquals(MemoryFile.Type.REFERENCE, MemoryFile.Type.parse("reference"));
        // 未知 / null / 空 → USER 兜底
        assertEquals(MemoryFile.Type.USER, MemoryFile.Type.parse("unknown"));
        assertEquals(MemoryFile.Type.USER, MemoryFile.Type.parse(null));
        assertEquals(MemoryFile.Type.USER, MemoryFile.Type.parse(""));
    }
}
