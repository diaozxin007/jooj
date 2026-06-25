package com.xilidou.marvis.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.config.JacksonConfig;
import com.xilidou.marvis.cron.CronConfig;
import com.xilidou.marvis.cron.CronService;
import com.xilidou.marvis.cron.CronStore;
import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.tool.ToolDefinition;
import com.xilidou.marvis.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link CronTool} 的派发行为(s14 3 个工具)。
 *
 * <p>跟 {@link TasksToolTest} 同模式 —— 不走 Spring 容器,直接 {@code new} 出来,
 * 用 {@code @TempDir} 隔离磁盘 I/O。
 */
class CronToolTest {

    @TempDir
    Path tempDir;

    private CronTool tool;
    private CronService service;

    @BeforeEach
    void setUp() {
        ObjectMapper json = JacksonConfig.newMapper();
        Path durable = tempDir.resolve(".scheduled_tasks.json");
        CronConfig cfg = new CronConfig(durable, 1000L, 200L);
        CronStore store = new CronStore(cfg, json);
        service = new CronService(store);
        tool = new CronTool(service);
    }

    private ToolResult call(String name, Map<String, Object> args) {
        return tool.execute(new ToolCall(name, args));
    }

    @Test
    @DisplayName("getTools 返回 3 个 ToolDefinition,名字严格对齐上游")
    void exposes_three_tool_definitions() {
        List<ToolDefinition> defs = tool.getTools();
        assertEquals(3, defs.size());
        List<String> names = defs.stream().map(ToolDefinition::getName).toList();
        assertTrue(names.contains("schedule_cron"));
        assertTrue(names.contains("list_crons"));
        assertTrue(names.contains("cancel_cron"));
    }

    @Test
    @DisplayName("schedule_cron:正常路径返回 'Scheduled <id>: ...'")
    void schedule_cron_returns_scheduled_message() {
        ToolResult r = call("schedule_cron", Map.of(
                "cron", "0 9 * * *",
                "prompt", "do X"));
        assertTrue(r.isSuccess(), "实际:" + r.getOutput());
        assertTrue(r.getOutput().startsWith("Scheduled cron_"),
                "实际:" + r.getOutput());
        assertTrue(r.getOutput().contains("0 9 * * *"));
        assertTrue(r.getOutput().contains("do X"));
    }

    @Test
    @DisplayName("schedule_cron:非法 cron → success=false + NL 错误")
    void schedule_cron_invalid_cron_returns_error() {
        ToolResult r = call("schedule_cron", Map.of(
                "cron", "60 * * * *",
                "prompt", "x"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().startsWith("Error:"));
    }

    @Test
    @DisplayName("schedule_cron:缺 cron → 友好错误")
    void schedule_cron_missing_cron_returns_error() {
        ToolResult r = call("schedule_cron", Map.of("prompt", "x"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().toLowerCase().contains("cron"));
    }

    @Test
    @DisplayName("schedule_cron:缺 prompt → 友好错误")
    void schedule_cron_missing_prompt_returns_error() {
        ToolResult r = call("schedule_cron", Map.of("cron", "0 9 * * *"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().toLowerCase().contains("prompt"));
    }

    @Test
    @DisplayName("list_crons:无 job → 'No scheduled cron jobs.'")
    void list_crons_empty() {
        ToolResult r = call("list_crons", Map.of());
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("No scheduled cron jobs"));
    }

    @Test
    @DisplayName("list_crons:有 job → 多行,含 ⏰ + cron + prompt + flags")
    void list_crons_returns_multiline() {
        service.schedule("0 9 * * *", "morning", true, false);
        service.schedule("0 18 * * *", "evening", false, true);

        ToolResult r = call("list_crons", Map.of());
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("⏰"), "实际:" + r.getOutput());
        assertTrue(r.getOutput().contains("0 9 * * *"));
        assertTrue(r.getOutput().contains("morning"));
        assertTrue(r.getOutput().contains("evening"));
        assertTrue(r.getOutput().contains("recurring=true"));
        assertTrue(r.getOutput().contains("recurring=false"));
        assertTrue(r.getOutput().contains("durable=true"));
    }

    @Test
    @DisplayName("cancel_cron:存在的 id → 'Cancelled <id>'")
    void cancel_cron_existing() {
        String id = service.schedule("0 9 * * *", "x", true, false);
        ToolResult r = call("cancel_cron", Map.of("job_id", id));
        assertTrue(r.isSuccess());
        assertEquals("Cancelled " + id, r.getOutput());
    }

    @Test
    @DisplayName("cancel_cron:不存在的 id → success=false + NL 错误")
    void cancel_cron_missing() {
        ToolResult r = call("cancel_cron", Map.of("job_id", "cron_999999"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("not found"));
    }

    @Test
    @DisplayName("cancel_cron:缺 job_id → 友好错误")
    void cancel_cron_missing_id() {
        ToolResult r = call("cancel_cron", Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().toLowerCase().contains("job_id"));
    }

    @Test
    @DisplayName("schedule_cron:recurring 默认 true,durable 默认 false")
    void schedule_cron_defaults() {
        call("schedule_cron", Map.of("cron", "0 9 * * *", "prompt", "x"));
        var jobs = service.list();
        assertEquals(1, jobs.size());
        assertTrue(jobs.get(0).isRecurring(), "默认 recurring=true");
        assertFalse(jobs.get(0).isDurable(), "默认 durable=false");
    }

    @Test
    @DisplayName("未知工具名 → 'Unknown tool: <name>'")
    void unknown_tool_returns_error() {
        ToolResult r = call("not_a_real_tool", Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("Unknown tool"));
    }
}
