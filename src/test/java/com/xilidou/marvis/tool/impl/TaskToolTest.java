package com.xilidou.marvis.tool.impl;

import com.xilidou.marvis.subagent.Subagent;
import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.tool.ToolDefinition;
import com.xilidou.marvis.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 锁定 {@link TaskTool}(s12 Stage 1 抽出来的标准 Tool 实现)的核心行为:
 * <ul>
 *   <li>缺 {@code description} 参数 → 友好错误</li>
 *   <li>{@code description} 空白 → 友好错误</li>
 *   <li>正常路径:转发到 {@link Subagent#spawn(String)},把返回值包成 success</li>
 *   <li>{@link Subagent} 抛异常 → 包成 fail ToolResult,不传播给上游</li>
 *   <li>{@code getTools()} 暴露的 schema 和 R4 之前内联实现一致</li>
 * </ul>
 *
 * <p>本测试不走 Spring 容器 —— TaskTool 只依赖一个 {@link Subagent},直接 Mockito mock
 * 后 {@code new TaskTool(mock)} 构造即可,跑得最快。
 */
class TaskToolTest {

    private Subagent subagent;
    private TaskTool tool;

    @BeforeEach
    void setUp() {
        subagent = mock(Subagent.class);
        tool = new TaskTool(subagent);
    }

    @Test
    @DisplayName("getName 返回 'task'")
    void name_is_task() {
        assertEquals("task", tool.getName());
    }

    @Test
    @DisplayName("getTools 返回唯一的 task 工具,带 description 必填的 schema")
    void tool_definition_has_correct_schema() {
        List<ToolDefinition> defs = tool.getTools();
        assertEquals(1, defs.size());
        ToolDefinition def = defs.get(0);
        assertEquals("task", def.getName());
        assertTrue(def.getDescription().toLowerCase().contains("subagent"));
        assertTrue(def.getInputSchema().getProperties().containsKey("description"));
        assertTrue(def.getInputSchema().getRequired().contains("description"));
    }

    @Test
    @DisplayName("缺 description 参数 → 友好错误")
    void missing_description_returns_error() {
        ToolResult result = tool.execute(new ToolCall("task", Map.of()));
        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().toLowerCase().contains("description"));
        verifyNoInteractions(subagent);
    }

    @Test
    @DisplayName("description 空白 → 友好错误")
    void blank_description_returns_error() {
        ToolResult result = tool.execute(new ToolCall("task", Map.of("description", "  ")));
        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().toLowerCase().contains("description"));
        verifyNoInteractions(subagent);
    }

    @Test
    @DisplayName("正常路径:转发到 subagent.spawn,返回 success + 子 agent 摘要")
    void delegates_to_subagent_and_wraps_result() {
        when(subagent.spawn("分析 X 模块")).thenReturn("X 模块的关键文件是 Foo.java");

        ToolResult result = tool.execute(new ToolCall("task",
                Map.of("description", "分析 X 模块")));

        assertTrue(result.isSuccess());
        assertEquals("X 模块的关键文件是 Foo.java", result.getOutput());
        verify(subagent, times(1)).spawn("分析 X 模块");
    }

    @Test
    @DisplayName("subagent.spawn 抛异常 → 包成 fail ToolResult,不传播")
    void wraps_subagent_exception() {
        when(subagent.spawn(any())).thenThrow(new RuntimeException("LLM 挂了"));

        ToolResult result = tool.execute(new ToolCall("task",
                Map.of("description", "any task")));

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().toLowerCase().contains("subagent failed"),
                "应包含 'Subagent failed' 标识,实际:" + result.getOutput());
        assertTrue(result.getOutput().contains("LLM 挂了"),
                "应包含原始异常 message,实际:" + result.getOutput());
    }

    @Test
    @DisplayName("非 task 名字 → 友好错误(防御性)")
    void unknown_tool_name_returns_error() {
        ToolResult result = tool.execute(new ToolCall("not_task",
                Map.of("description", "x")));
        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().toLowerCase().contains("unknown tool"));
        verifyNoInteractions(subagent);
    }
}
