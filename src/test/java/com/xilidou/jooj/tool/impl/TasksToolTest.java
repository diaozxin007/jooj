package com.xilidou.jooj.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.tasks.TaskConfig;
import com.xilidou.jooj.tasks.TaskService;
import com.xilidou.jooj.tasks.TaskStore;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link TasksTool} 的派发行为(s12 5 个工具):
 * <ul>
 *   <li>create_task / list_tasks / get_task / claim_task / complete_task 都能被正确派发</li>
 *   <li>未知工具名 → 友好错误</li>
 *   <li>缺必填参数 → 友好错误</li>
 *   <li>schema 暴露 5 个 ToolDefinition</li>
 * </ul>
 *
 * <p>跟 {@link TodoToolTest} 同模式 —— 不走 Spring 容器,直接 {@code new} 出来,
 * 用 {@code @TempDir} 隔离磁盘 I/O。
 */
class TasksToolTest {

    @TempDir
    Path tempDir;

    private TasksTool tool;
    private TaskService service;

    @BeforeEach
    void setUp() {
        ObjectMapper json = JsonMappers.newMapper();
        TaskConfig config = new TaskConfig(tempDir);
        TaskStore store = new TaskStore(config, json);
        service = new TaskService(store);
        tool = new TasksTool(service, json);
    }

    private ToolResult call(String name, Map<String, Object> args) {
        return tool.execute(new ToolCall(name, args));
    }

    @Test
    @DisplayName("getTools 返回 5 个 ToolDefinition,名字严格对齐上游")
    void exposes_five_tool_definitions() {
        List<ToolDefinition> defs = tool.getTools();
        assertEquals(5, defs.size());
        List<String> names = defs.stream().map(ToolDefinition::getName).toList();
        assertTrue(names.contains("create_task"));
        assertTrue(names.contains("list_tasks"));
        assertTrue(names.contains("get_task"));
        assertTrue(names.contains("claim_task"));
        assertTrue(names.contains("complete_task"));
    }

    @Test
    @DisplayName("create_task:正常路径返回 'Created <id>: <subject>'")
    void create_task_returns_created_message() {
        ToolResult r = call("create_task",
                Map.of("subject", "implement parser"));
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().startsWith("Created task_"),
                "实际:" + r.getOutput());
        assertTrue(r.getOutput().contains("implement parser"));
    }

    @Test
    @DisplayName("create_task:带 blockedBy 时,Created 字符串包含 deps")
    void create_task_with_blocked_by_includes_deps() {
        ToolResult r = call("create_task", Map.of(
                "subject", "second",
                "blockedBy", List.of("task_first_0001")));
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("blockedBy: task_first_0001"),
                "实际:" + r.getOutput());
    }

    @Test
    @DisplayName("create_task:缺 subject → 友好错误")
    void create_task_missing_subject_returns_error() {
        ToolResult r = call("create_task", Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().toLowerCase().contains("subject"));
    }

    @Test
    @DisplayName("list_tasks:无 task → 提示 'No tasks. Use create_task'")
    void list_tasks_empty() {
        ToolResult r = call("list_tasks", Map.of());
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("No tasks"),
                "实际:" + r.getOutput());
    }

    @Test
    @DisplayName("list_tasks:有 task → 多行输出,含图标 + status")
    void list_tasks_returns_multiline_with_icons() {
        service.create("A", "", List.of());

        ToolResult r = call("list_tasks", Map.of());
        assertTrue(r.isSuccess());
        // 上游 Python:`{icon} {id}: {subject} [{status}]`
        // 我们也用 ○(pending),输出含 subject + 状态
        assertTrue(r.getOutput().contains("○") || r.getOutput().contains("[pending]"),
                "实际:" + r.getOutput());
        assertTrue(r.getOutput().contains("A"));
    }

    @Test
    @DisplayName("get_task:存在的 id → 返回 JSON,含 6 字段")
    void get_task_returns_full_json() {
        String id = service.create("X", "desc", List.of());

        ToolResult r = call("get_task", Map.of("task_id", id));
        assertTrue(r.isSuccess());
        // 检查 JSON 字段都在
        assertTrue(r.getOutput().contains("\"id\""));
        assertTrue(r.getOutput().contains("\"subject\""));
        assertTrue(r.getOutput().contains("\"description\""));
        assertTrue(r.getOutput().contains("\"status\""));
        assertTrue(r.getOutput().contains("\"blockedBy\""));
        assertTrue(r.getOutput().contains(id));
    }

    @Test
    @DisplayName("get_task:不存在的 id → 'Error: Task X not found'")
    void get_task_missing_returns_error() {
        ToolResult r = call("get_task", Map.of("task_id", "task_nope_0001"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("not found"),
                "实际:" + r.getOutput());
    }

    @Test
    @DisplayName("claim_task:成功路径 → 'Claimed <id> (<subject>)'")
    void claim_task_succeeds() {
        String id = service.create("X", "", List.of());

        ToolResult r = call("claim_task", Map.of("task_id", id));
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().startsWith("Claimed " + id),
                "实际:" + r.getOutput());
    }

    @Test
    @DisplayName("claim_task:失败路径 → success=false + NL 错误字符串")
    void claim_task_already_in_progress_returns_failure() {
        String id = service.create("X", "", List.of());
        service.claim(id, "agent");

        ToolResult r = call("claim_task", Map.of("task_id", id));
        assertFalse(r.isSuccess(), "应该 success=false");
        assertTrue(r.getOutput().contains("cannot claim"),
                "实际:" + r.getOutput());
    }

    @Test
    @DisplayName("complete_task:成功 + 解锁后续 task → 字符串包含 'Unblocked:'")
    void complete_task_with_unblocked_returns_unblock_line() {
        String aId = service.create("A", "", List.of());
        @SuppressWarnings("unused") String bId = service.create("B", "", List.of(aId));
        service.claim(aId, "agent");

        ToolResult r = call("complete_task", Map.of("task_id", aId));
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().startsWith("Completed " + aId),
                "实际:" + r.getOutput());
        assertTrue(r.getOutput().contains("Unblocked: B"),
                "实际:" + r.getOutput());
    }

    @Test
    @DisplayName("complete_task:失败路径(非 IN_PROGRESS)→ success=false")
    void complete_task_pending_returns_failure() {
        String id = service.create("X", "", List.of());

        ToolResult r = call("complete_task", Map.of("task_id", id));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("cannot complete"),
                "实际:" + r.getOutput());
    }

    @Test
    @DisplayName("未知工具名 → 'Unknown tool: <name>'")
    void unknown_tool_returns_error() {
        ToolResult r = call("not_a_real_tool", Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("Unknown tool"));
    }

    @Test
    @DisplayName("get_task / claim_task / complete_task 缺 task_id → 友好错误")
    void missing_task_id_returns_error() {
        for (String t : List.of("get_task", "claim_task", "complete_task")) {
            ToolResult r = call(t, Map.of());
            assertFalse(r.isSuccess(), t + " 应该失败");
            assertTrue(r.getOutput().toLowerCase().contains("task_id"),
                    t + " 实际:" + r.getOutput());
        }
    }
}
