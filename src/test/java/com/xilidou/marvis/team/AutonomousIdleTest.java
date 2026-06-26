package com.xilidou.marvis.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.config.JacksonConfig;
import com.xilidou.marvis.tasks.TaskConfig;
import com.xilidou.marvis.tasks.TaskRecord;
import com.xilidou.marvis.tasks.TaskService;
import com.xilidou.marvis.tasks.TaskStatus;
import com.xilidou.marvis.tasks.TaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link AutonomousIdle} 的 scan + tryClaim 行为。
 *
 * <p>覆盖:
 * <ul>
 *   <li>scanAvailable 只返 pending + 无 owner + blockedBy 已完成</li>
 *   <li>tryClaim 成功路径 → in_progress + owner 设上</li>
 *   <li>tryClaim 没活返 empty</li>
 *   <li>tryClaim 多次只能各 claim 不同的 task</li>
 *   <li>blockedBy 未完成的 task 不被列入</li>
 * </ul>
 */
class AutonomousIdleTest {

    @TempDir
    Path tempDir;

    private TaskService taskService;
    private AutonomousIdle idle;

    @BeforeEach
    void setUp() {
        ObjectMapper json = JacksonConfig.newMapper();
        TaskConfig config = new TaskConfig(tempDir);
        TaskStore store = new TaskStore(config, json);
        taskService = new TaskService(store);
        idle = new AutonomousIdle(taskService);
    }

    @Test
    @DisplayName("scanAvailable 空看板返空 list")
    void scan_empty_board() {
        assertTrue(idle.scanAvailable().isEmpty());
    }

    @Test
    @DisplayName("scanAvailable 返回 pending + 无 owner 的 task")
    void scan_returns_pending_unclaimed() {
        String idA = taskService.create("Task A", "do A", List.of());
        String idB = taskService.create("Task B", "do B", List.of());

        List<TaskRecord> available = idle.scanAvailable();
        assertEquals(2, available.size());
        assertTrue(available.stream().anyMatch(t -> t.getId().equals(idA)));
        assertTrue(available.stream().anyMatch(t -> t.getId().equals(idB)));
    }

    @Test
    @DisplayName("scanAvailable 不返已被 claim 的 task")
    void scan_skips_claimed() {
        String idA = taskService.create("Task A", "", List.of());
        String idB = taskService.create("Task B", "", List.of());

        // alice 抢走 A
        taskService.claim(idA, "alice");

        List<TaskRecord> available = idle.scanAvailable();
        assertEquals(1, available.size(), "已 claim 的 A 不应出现");
        assertEquals(idB, available.get(0).getId());
    }

    @Test
    @DisplayName("scanAvailable 不返 blockedBy 未完成的 task")
    void scan_respects_dependencies() {
        String idA = taskService.create("Task A", "", List.of());
        String idB = taskService.create("Task B", "", List.of(idA));   // B blocked by A

        // A 还没完成 → B 不应被列入
        List<TaskRecord> available = idle.scanAvailable();
        assertEquals(1, available.size());
        assertEquals(idA, available.get(0).getId(), "B 被 A 阻塞,不应出现在 available");

        // A 完成后 B 应可领
        taskService.claim(idA, "alice");
        taskService.complete(idA);
        List<TaskRecord> afterUnblock = idle.scanAvailable();
        assertEquals(1, afterUnblock.size());
        assertEquals(idB, afterUnblock.get(0).getId());
    }

    @Test
    @DisplayName("tryClaim 成功路径 → 返 TaskRecord with status=in_progress")
    void try_claim_happy_path() {
        String id = taskService.create("Task A", "", List.of());

        Optional<TaskRecord> claimed = idle.tryClaim("alice");
        assertTrue(claimed.isPresent());
        assertEquals(id, claimed.get().getId());
        assertEquals(TaskStatus.IN_PROGRESS, claimed.get().getStatus());
        assertEquals("alice", claimed.get().getOwner());
    }

    @Test
    @DisplayName("tryClaim 没活返 empty")
    void try_claim_empty_board() {
        assertTrue(idle.tryClaim("alice").isEmpty());
    }

    @Test
    @DisplayName("两个 agent 各自 tryClaim 拿到不同的 task")
    void two_agents_claim_different_tasks() {
        String idA = taskService.create("Task A", "", List.of());
        String idB = taskService.create("Task B", "", List.of());

        Optional<TaskRecord> aliceTask = idle.tryClaim("alice");
        Optional<TaskRecord> bobTask = idle.tryClaim("bob");

        assertTrue(aliceTask.isPresent());
        assertTrue(bobTask.isPresent());
        assertNotEquals(aliceTask.get().getId(), bobTask.get().getId(),
                "两个 agent 应该各自拿到不同的 task");
    }

    @Test
    @DisplayName("已完成 / in_progress 的 task 不会被 tryClaim 重复领")
    void try_claim_skips_non_pending() {
        String id = taskService.create("Task A", "", List.of());
        taskService.claim(id, "alice");                    // → in_progress

        // bob 再 tryClaim 应该没活
        assertTrue(idle.tryClaim("bob").isEmpty());

        taskService.complete(id);                           // → completed
        // bob 仍然没活
        assertTrue(idle.tryClaim("bob").isEmpty());
    }
}
