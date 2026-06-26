package com.xilidou.jooj.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link TaskStore} 文件 I/O 层的核心行为:
 * <ul>
 *   <li>write/read roundtrip 字段不丢</li>
 *   <li>list 按文件名(≈ 时间)排序</li>
 *   <li>不存在的 id 读返回 empty,不抛</li>
 *   <li>id 路径穿越拒绝</li>
 *   <li>JSON 缺字段(向后兼容)不抛</li>
 *   <li>delete 幂等</li>
 * </ul>
 *
 * <p>跟 {@link com.xilidou.jooj.memory.MemoryStoreTest} 同模式 —— 直接 {@code new TaskStore(...)},
 * 不走 Spring 容器,通过 {@code @TempDir} 隔离磁盘。
 */
class TaskStoreTest {

    @TempDir
    Path tempDir;

    private TaskStore store;
    private final ObjectMapper json = JacksonConfig.newMapper();

    @BeforeEach
    void setUp() {
        TaskConfig config = new TaskConfig(tempDir);
        store = new TaskStore(config, json);
    }

    @Test
    @DisplayName("write/read roundtrip:6 字段全部保留")
    void write_then_read_roundtrip() {
        TaskRecord t = new TaskRecord(
                "task_1729000000_0001",
                "implement parser",
                "Use ANTLR for speed",
                TaskStatus.PENDING,
                null,
                List.of("task_1728999000_0001")
        );
        store.write(t);

        Optional<TaskRecord> read = store.read("task_1729000000_0001");
        assertTrue(read.isPresent());
        TaskRecord r = read.get();
        assertEquals("task_1729000000_0001", r.getId());
        assertEquals("implement parser", r.getSubject());
        assertEquals("Use ANTLR for speed", r.getDescription());
        assertEquals(TaskStatus.PENDING, r.getStatus());
        assertNull(r.getOwner());
        assertEquals(List.of("task_1728999000_0001"), r.getBlockedBy());
    }

    @Test
    @DisplayName("write 同 id 是覆盖语义,不是追加")
    void write_overwrites_existing() {
        TaskRecord t1 = new TaskRecord("task_1_0001", "v1",
                "", TaskStatus.PENDING, null, List.of());
        store.write(t1);

        TaskRecord t2 = new TaskRecord("task_1_0001", "v2",
                "updated", TaskStatus.IN_PROGRESS, "agent", List.of());
        store.write(t2);

        Optional<TaskRecord> read = store.read("task_1_0001");
        assertTrue(read.isPresent());
        assertEquals("v2", read.get().getSubject());
        assertEquals(TaskStatus.IN_PROGRESS, read.get().getStatus());
        assertEquals("agent", read.get().getOwner());
    }

    @Test
    @DisplayName("list 按文件名(unix ts)升序;ID 自带时间序")
    void list_sorted_by_id() {
        store.write(new TaskRecord("task_2_0001", "second",
                "", TaskStatus.PENDING, null, List.of()));
        store.write(new TaskRecord("task_1_0001", "first",
                "", TaskStatus.PENDING, null, List.of()));
        store.write(new TaskRecord("task_3_0001", "third",
                "", TaskStatus.PENDING, null, List.of()));

        List<TaskRecord> all = store.list();
        assertEquals(3, all.size());
        assertEquals("first", all.get(0).getSubject());
        assertEquals("second", all.get(1).getSubject());
        assertEquals("third", all.get(2).getSubject());
    }

    @Test
    @DisplayName("不存在的 id → read 返回 empty,不抛")
    void read_missing_returns_empty() {
        Optional<TaskRecord> r = store.read("task_does_not_exist_0001");
        assertTrue(r.isEmpty());
    }

    @Test
    @DisplayName("exists 反映文件存在性")
    void exists_reflects_file_existence() {
        assertFalse(store.exists("task_x_0001"));
        store.write(new TaskRecord("task_x_0001", "x",
                "", TaskStatus.PENDING, null, List.of()));
        assertTrue(store.exists("task_x_0001"));
    }

    @Test
    @DisplayName("空目录 → list 返回空 List")
    void list_empty_dir_returns_empty() {
        List<TaskRecord> all = store.list();
        assertTrue(all.isEmpty());
    }

    @Test
    @DisplayName("不是 task_*.json 的文件被忽略")
    void list_ignores_non_task_files() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("README.md"), "# tasks");
        Files.writeString(tempDir.resolve("MEMORY.md"), "junk");
        Files.writeString(tempDir.resolve("not_a_task.json"), "{}");

        store.write(new TaskRecord("task_1_0001", "real",
                "", TaskStatus.PENDING, null, List.of()));

        List<TaskRecord> all = store.list();
        assertEquals(1, all.size());
        assertEquals("real", all.get(0).getSubject());
    }

    @Test
    @DisplayName("id 含路径穿越字符 → 拒绝")
    void rejects_path_traversal_id() {
        TaskRecord bad = new TaskRecord("../etc/passwd", "x",
                "", TaskStatus.PENDING, null, List.of());
        assertThrows(IllegalArgumentException.class, () -> store.write(bad));
        assertThrows(IllegalArgumentException.class, () -> store.read("../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> store.exists("../etc/passwd"));
    }

    @Test
    @DisplayName("JSON 缺字段(只有 id+subject+status)→ 不抛,默认值填上")
    void parses_partial_json_with_defaults() throws Exception {
        Files.createDirectories(tempDir);
        Path file = tempDir.resolve("task_minimal_0001.json");
        Files.writeString(file, "{\"id\":\"task_minimal_0001\",\"subject\":\"x\",\"status\":\"pending\"}");

        Optional<TaskRecord> r = store.read("task_minimal_0001");
        assertTrue(r.isPresent());
        assertEquals("x", r.get().getSubject());
        assertEquals(TaskStatus.PENDING, r.get().getStatus());
        // description 默认空串(POJO 默认值或反序列化时填),blockedBy 默认空 list
        assertNotNull(r.get().getBlockedBy());
    }

    @Test
    @DisplayName("delete 已存在文件 → true;不存在 → false,不抛")
    void delete_idempotent() {
        store.write(new TaskRecord("task_d_0001", "x",
                "", TaskStatus.PENDING, null, List.of()));
        assertTrue(store.delete("task_d_0001"));
        assertFalse(store.delete("task_d_0001"));
        assertFalse(store.exists("task_d_0001"));
    }

    @Test
    @DisplayName("文件名是 task_<id>.json,跟 Python 的 _task_path 一致")
    void file_naming_matches_upstream() {
        store.write(new TaskRecord("task_1729000000_3812", "x",
                "", TaskStatus.PENDING, null, List.of()));
        assertTrue(Files.exists(tempDir.resolve("task_1729000000_3812.json")),
                "tasks dir 里应该有 task_1729000000_3812.json,实际:" + tempDir);
    }
}
