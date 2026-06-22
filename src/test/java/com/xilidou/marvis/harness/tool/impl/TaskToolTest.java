package com.xilidou.marvis.harness.tool.impl;

import com.xilidou.marvis.harness.base.ToolRegistry;
import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.hook.HookManager;
import com.xilidou.marvis.harness.http.MockAnthropicClient;
import com.xilidou.marvis.harness.http.ResponseFixtures;
import com.xilidou.marvis.harness.subagent.Subagent;
import com.xilidou.marvis.harness.JacksonConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 TaskTool 的核心行为：
 * <ul>
 *   <li>正常任务 → 调 Subagent.spawn → 返回 summary</li>
 *   <li>缺 description / 空白 description → 友好错误</li>
 *   <li>Subagent 抛异常 → 友好错误（不让父 loop 崩）</li>
 * </ul>
 */
class TaskSkillTest {

    @Test
    void normal_task_returns_subagent_summary() {
        // Subagent 跑完返回 "summary text"
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("subagent summary text")
        );
        Subagent subagent = new Subagent(
                mock, "test-model", new ToolRegistry(),
                JacksonConfig.newMapper(), new HookManager());

        TaskTool task = new TaskTool(subagent);
        ToolResult result = task.execute(new ToolCall("task",
                Map.of("description", "do this work")));

        assertTrue(result.isSuccess());
        assertEquals("subagent summary text", result.getOutput());
    }

    @Test
    void missing_description_returns_error() {
        Subagent subagent = neverCalledSubagent();
        TaskTool task = new TaskTool(subagent);
        ToolResult result = task.execute(new ToolCall("task", Map.of()));

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().toLowerCase().contains("description"));
    }

    @Test
    void blank_description_returns_error() {
        Subagent subagent = neverCalledSubagent();
        TaskTool task = new TaskTool(subagent);
        ToolResult result = task.execute(new ToolCall("task", Map.of("description", "   ")));

        assertFalse(result.isSuccess());
    }

    @Test
    void subagent_exception_returns_error_not_throws() {
        // 用一个 mock 让 Subagent 第一次 createMessage 就抛
        MockAnthropicClient mock = MockAnthropicClient.throwing(
                new RuntimeException("network down"));
        Subagent subagent = new Subagent(
                mock, "test-model", new ToolRegistry(),
                JacksonConfig.newMapper(), new HookManager());

        TaskTool task = new TaskTool(subagent);
        // 不该让父 Agent 崩，应该返回 ToolResult(false, ...)
        ToolResult result = task.execute(new ToolCall("task",
                Map.of("description", "do work")));

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("Subagent failed"));
    }

    @Test
    void wrong_tool_name_returns_error() {
        Subagent subagent = neverCalledSubagent();
        TaskTool task = new TaskTool(subagent);
        ToolResult result = task.execute(new ToolCall("not_task", Map.of("description", "x")));

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("Unknown tool"));
    }

    @Test
    void tool_definition_has_required_field() {
        Subagent subagent = neverCalledSubagent();
        TaskTool task = new TaskTool(subagent);
        var tools = task.getTools();

        assertEquals(1, tools.size());
        assertEquals("task", tools.get(0).getName());
        assertTrue(tools.get(0).getInputSchema().getRequired().contains("description"));
    }

    private Subagent neverCalledSubagent() {
        return new Subagent(
                MockAnthropicClient.throwing(new RuntimeException("should not be called")),
                "test-model", new ToolRegistry(),
                JacksonConfig.newMapper(), new HookManager());
    }
}
