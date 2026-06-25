package com.xilidou.marvis.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link TaskService} 业务封装的核心行为:
 * <ul>
 *   <li>create-then-get</li>
 *   <li>canStart 默认 true(无 dep)</li>
 *   <li>blockedBy 含 missing dep → false</li>
 *   <li>blockedBy 含 in_progress dep → false</li>
 *   <li>claim 重复拒绝(已 in_progress)</li>
 *   <li>claim PENDING 但有未完成 dep 拒绝(返回 NL "Blocked by")</li>
 *   <li>complete 切状态 + 触发 unblocked 字符串</li>
 *   <li>complete 不在 in_progress 拒绝</li>
 * </ul>
 */
class TaskServiceTest {

    @TempDir
    Path tempDir;

    private TaskService service;
    private TaskStore store;

    @BeforeEach
    void setUp() {
        ObjectMapper json = JacksonConfig.newMapper();
        TaskConfig config = new TaskConfig(tempDir);
        store = new TaskStore(config, json);
        service = new TaskService(store);
    }

    @Test
    @DisplayName("create-then-get:返回 id,落盘的字段对得上")
    void create_then_get() {
        String id = service.create("plan parser", "use ANTLR", List.of());

        assertNotNull(id);
        assertTrue(id.startsWith("task_"), "id 必须以 task_ 开头,实际:" + id);

        Optional<TaskRecord> r = service.get(id);
        assertTrue(r.isPresent());
        assertEquals("plan parser", r.get().getSubject());
        assertEquals("use ANTLR", r.get().getDescription());
        assertEquals(TaskStatus.PENDING, r.get().getStatus());
        assertNull(r.get().getOwner());
        assertTrue(r.get().getBlockedBy().isEmpty());
    }

    @Test
    @DisplayName("create 时 description / blockedBy 可省略(对应 Python 默认值)")
    void create_with_defaults() {
        String id = service.create("just a subject", null, null);
        TaskRecord r = service.get(id).orElseThrow();
        assertEquals("", r.getDescription(), "description 默认��串");
        assertNotNull(r.getBlockedBy(), "blockedBy 默认空 list,不是 null");
        assertTrue(r.getBlockedBy().isEmpty());
    }

    @Test
    @DisplayName("create 时 subject 空 → IllegalArgumentException")
    void create_blank_subject_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("", "", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(null, "", List.of()));
    }

    @Test
    @DisplayName("canStart:无 dep 默认可启动")
    void canStart_no_deps_returns_true() {
        String id = service.create("task A", "", List.of());
        assertTrue(service.canStart(id));
    }

    @Test
    @DisplayName("canStart:dep 文件不存在 → false(defensive)")
    void canStart_missing_dep_returns_false() {
        String id = service.create("task A", "", List.of("task_missing_0001"));
        assertFalse(service.canStart(id),
                "missing dep 应该被视为 blocked,不抛异常");
    }

    @Test
    @DisplayName("canStart:dep 还在 PENDING / IN_PROGRESS → false")
    void canStart_pending_dep_returns_false() {
        String depId = service.create("dep", "", List.of());
        String id = service.create("task", "", List.of(depId));

        // dep 还是 PENDING
        assertFalse(service.canStart(id));

        // claim dep → IN_PROGRESS
        service.claim(depId, "agent");
        assertFalse(service.canStart(id), "in_progress 仍是 blocked");

        // complete dep → COMPLETED
        service.complete(depId);
        assertTrue(service.canStart(id), "dep 完成后才解锁");
    }

    @Test
    @DisplayName("claim 不存在的 task → 返回 'Error: Task X not found'")
    void claim_missing_returns_error() {
        String result = service.claim("task_nope_0001", "agent");
        assertTrue(result.startsWith("Error: Task task_nope_0001 not found"),
                "实际:" + result);
    }

    @Test
    @DisplayName("claim 成功:PENDING + canStart → IN_PROGRESS,owner 写入,返回 'Claimed ...'")
    void claim_pending_succeeds() {
        String id = service.create("task A", "", List.of());

        String result = service.claim(id, "agent");
        assertTrue(result.startsWith("Claimed " + id),
                "实际:" + result);
        assertTrue(result.contains("task A"),
                "Claimed 字符串应含 subject,实际:" + result);

        TaskRecord r = service.get(id).orElseThrow();
        assertEquals(TaskStatus.IN_PROGRESS, r.getStatus());
        assertEquals("agent", r.getOwner());
    }

    @Test
    @DisplayName("claim 已 IN_PROGRESS → 返回 'Task X is in_progress, cannot claim'")
    void claim_in_progress_rejected() {
        String id = service.create("task A", "", List.of());
        service.claim(id, "agent");

        String result = service.claim(id, "agent");
        assertTrue(result.contains("is in_progress"),
                "应该拒绝重复 claim,实际:" + result);
        assertTrue(result.contains("cannot claim"));
    }

    @Test
    @DisplayName("claim PENDING 但有未完成 dep → 返回 'Blocked by: [...]'")
    void claim_pending_with_incomplete_deps_returns_blocked() {
        String depId = service.create("dep", "", List.of());
        String id = service.create("task", "", List.of(depId));

        String result = service.claim(id, "agent");
        assertTrue(result.startsWith("Blocked by:"),
                "实际:" + result);
        assertTrue(result.contains(depId), "Blocked by 列表应含 dep id,实际:" + result);

        // 状态不变
        assertEquals(TaskStatus.PENDING, service.get(id).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("complete:IN_PROGRESS → COMPLETED,无后续依赖时只返回 'Completed ...'")
    void complete_in_progress_succeeds_no_unblock() {
        String id = service.create("task A", "", List.of());
        service.claim(id, "agent");

        String result = service.complete(id);
        assertTrue(result.startsWith("Completed " + id),
                "实际:" + result);
        assertFalse(result.contains("Unblocked:"),
                "无后续 task 时不能凑出 Unblocked: 行");

        assertEquals(TaskStatus.COMPLETED, service.get(id).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("complete:解锁后续 PENDING task,返回 'Unblocked: <subjects>'")
    void complete_triggers_unblocked_listing() {
        String aId = service.create("A", "", List.of());
        String bId = service.create("B", "", List.of(aId));   // B blocked by A
        String cId = service.create("C", "", List.of(aId));   // C blocked by A
        // D 无 blockedBy:已经 canStart,但 unblocked 列表只算"有 blockedBy 的 task"
        @SuppressWarnings("unused") String dId = service.create("D", "", List.of());

        service.claim(aId, "agent");
        String result = service.complete(aId);

        assertTrue(result.startsWith("Completed " + aId), "实际:" + result);
        assertTrue(result.contains("Unblocked:"), "完成 A 后应解锁 B/C,实际:" + result);
        assertTrue(result.contains("B"));
        assertTrue(result.contains("C"));
        assertFalse(result.contains("D"),
                "D 没有 blockedBy,不计入 Unblocked(对齐上游)");
    }

    @Test
    @DisplayName("complete 非 IN_PROGRESS → 'Task X is pending, cannot complete'")
    void complete_pending_rejected() {
        String id = service.create("task A", "", List.of());

        String result = service.complete(id);
        assertTrue(result.contains("is pending"),
                "实际:" + result);
        assertTrue(result.contains("cannot complete"));
    }

    @Test
    @DisplayName("complete 不存在 → 'Error: Task X not found'")
    void complete_missing_returns_error() {
        String result = service.complete("task_nope_0001");
        assertTrue(result.startsWith("Error: Task task_nope_0001 not found"),
                "实际:" + result);
    }

    @Test
    @DisplayName("create 多次,id 都不重复(同秒内 4 位随机数撞概率 1/10000)")
    void create_generates_unique_ids() {
        String id1 = service.create("a", "", List.of());
        String id2 = service.create("b", "", List.of());
        String id3 = service.create("c", "", List.of());
        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);
        assertNotEquals(id1, id3);
    }
}
