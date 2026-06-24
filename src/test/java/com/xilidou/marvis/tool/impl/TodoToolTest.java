package com.xilidou.marvis.tool.impl;

import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.tool.ToolResult;
import com.xilidou.marvis.todo.TodoStatus;
import com.xilidou.marvis.todo.TodoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 TodoTool 的核心行为：
 * <ul>
 *   <li>正常的 todos 列表能被解析并存入 TodoStore</li>
 *   <li>非法 status / 缺 content / 错类型 → 友好错误，不污染 store</li>
 *   <li>多次调用是整体替换（不是追加）</li>
 * </ul>
 */
class TodoToolTest {

    private TodoStore store;
    private TodoTool skill;

    @BeforeEach
    void setUp() {
        store = new TodoStore();
        skill = new TodoTool(store);
    }

    private ToolResult call(Object todosArg) {
        return skill.execute(new ToolCall("todo_write", Map.of("todos", todosArg)));
    }

    @Test
    @DisplayName("LLM 给的标准格式：[{content, status}, ...] 能被解析")
    void parses_valid_todos() {
        ToolResult result = call(List.of(
                Map.of("content", "step 1", "status", "pending"),
                Map.of("content", "step 2", "status", "in_progress"),
                Map.of("content", "step 3", "status", "completed")
        ));

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("3 tasks"));
        assertEquals(3, store.size());
        assertEquals(TodoStatus.IN_PROGRESS, store.snapshot().get(1).getStatus());
    }

    @Test
    @DisplayName("第二次调用整体替换（不是追加）")
    void second_call_replaces_not_appends() {
        call(List.of(Map.of("content", "old", "status", "pending")));
        call(List.of(
                Map.of("content", "new1", "status", "pending"),
                Map.of("content", "new2", "status", "pending")
        ));

        assertEquals(2, store.size());
        assertEquals("new1", store.snapshot().get(0).getContent());
    }

    @Test
    @DisplayName("非法 status → 友好错误，store 不变")
    void invalid_status_returns_error() {
        // 先放一个有效 todo
        call(List.of(Map.of("content", "old", "status", "pending")));

        // 再用错误 status
        ToolResult result = call(List.of(
                Map.of("content", "bad", "status", "DONE")  // 不是 pending/in_progress/completed
        ));

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().toLowerCase().contains("error"));
        // store 没被污染
        assertEquals(1, store.size());
        assertEquals("old", store.snapshot().get(0).getContent());
    }

    @Test
    @DisplayName("缺 content → 友好错误")
    void missing_content_returns_error() {
        ToolResult result = call(List.of(
                Map.of("status", "pending")  // 缺 content
        ));

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("content"));
    }

    @Test
    @DisplayName("空 content → 友好错误")
    void blank_content_returns_error() {
        ToolResult result = call(List.of(
                Map.of("content", "  ", "status", "pending")
        ));

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("content"));
    }

    @Test
    @DisplayName("空数组 → 成功（清空 todos）")
    void empty_array_clears_todos() {
        call(List.of(Map.of("content", "old", "status", "pending")));
        ToolResult result = call(List.of());

        assertTrue(result.isSuccess());
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("缺 todos 字段 → 友好错误")
    void missing_todos_arg_returns_error() {
        ToolResult result = skill.execute(new ToolCall("todo_write", Map.of()));
        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("todos"));
    }

    @Test
    @DisplayName("getTools 返回正确的 schema")
    void tool_definition_has_correct_schema() {
        var tools = skill.getTools();
        assertEquals(1, tools.size());
        assertEquals("todo_write", tools.get(0).getName());
        // input_schema 必须有 todos 字段
        assertTrue(tools.get(0).getInputSchema().getProperties().containsKey("todos"));
    }
}
