package com.xilidou.marvis.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.config.JacksonConfig;
import com.xilidou.marvis.subagent.Teammate;
import com.xilidou.marvis.team.MessageBus;
import com.xilidou.marvis.team.TeamConfig;
import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.tool.ToolDefinition;
import com.xilidou.marvis.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 锁定 {@link TeamTool} 的派发行为(s15 3 个工具)。
 *
 * <p>不走 Spring 容器,直接 {@code new}。{@link Teammate} 用 Mockito mock —— 不需要真起 daemon thread,
 * 测的是"工具 → service" 的路由 + 错误处理。{@link MessageBus} 真实例 + tempdir。
 */
class TeamToolTest {

    @TempDir
    Path tempDir;

    private TeamTool tool;
    private MessageBus bus;
    private Teammate teammate;

    @BeforeEach
    void setUp() {
        ObjectMapper json = JacksonConfig.newMapper();
        bus = new MessageBus(new TeamConfig(tempDir.resolve("mailboxes")), json);
        teammate = Mockito.mock(Teammate.class);
        tool = new TeamTool(teammate, bus);
    }

    private ToolResult call(String name, Map<String, Object> args) {
        return tool.execute(new ToolCall(name, args));
    }

    @Test
    @DisplayName("getTools 返回 3 个 ToolDefinition,名字严格对齐上游")
    void exposes_three_tools() {
        List<ToolDefinition> defs = tool.getTools();
        assertEquals(3, defs.size());
        List<String> names = defs.stream().map(ToolDefinition::getName).toList();
        assertTrue(names.contains("spawn_teammate"));
        assertTrue(names.contains("send_message"));
        assertTrue(names.contains("check_inbox"));
    }

    @Test
    @DisplayName("spawn_teammate 正常路径:转发到 Teammate.spawn 并返回 success")
    void spawn_teammate_happy_path() {
        when(teammate.spawn("alice", "backend dev", "set up DB"))
                .thenReturn("Spawned alice as backend dev");

        ToolResult r = call("spawn_teammate", Map.of(
                "name", "alice",
                "role", "backend dev",
                "prompt", "set up DB"));

        assertTrue(r.isSuccess());
        assertEquals("Spawned alice as backend dev", r.getOutput());
        verify(teammate).spawn("alice", "backend dev", "set up DB");
    }

    @Test
    @DisplayName("spawn_teammate Teammate 返 Error 字符串 → success=false")
    void spawn_teammate_returns_error_when_service_fails() {
        when(teammate.spawn(any(), any(), any()))
                .thenReturn("Error: teammate 'alice' already exists");

        ToolResult r = call("spawn_teammate", Map.of(
                "name", "alice", "role", "x", "prompt", "y"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().startsWith("Error:"));
    }

    @Test
    @DisplayName("spawn_teammate 缺参 → 友好错误")
    void spawn_teammate_missing_args() {
        assertFalse(call("spawn_teammate", Map.of("role", "x", "prompt", "y")).isSuccess());
        assertFalse(call("spawn_teammate", Map.of("name", "a", "prompt", "y")).isSuccess());
        assertFalse(call("spawn_teammate", Map.of("name", "a", "role", "x")).isSuccess());
    }

    @Test
    @DisplayName("send_message 成功路径:消息进入对方 inbox")
    void send_message_writes_to_inbox() {
        ToolResult r = call("send_message", Map.of(
                "to", "alice", "content", "please retry"));

        assertTrue(r.isSuccess());
        assertEquals("Sent to alice", r.getOutput());
        // alice 的 inbox 应有一条来自 lead 的消息
        var msgs = bus.readInbox("alice");
        assertEquals(1, msgs.size());
        assertEquals("lead", msgs.get(0).getFrom());
        assertEquals("please retry", msgs.get(0).getContent());
    }

    @Test
    @DisplayName("send_message 缺 to/content → 友好错误")
    void send_message_missing_args() {
        assertFalse(call("send_message", Map.of("content", "x")).isSuccess());
        assertFalse(call("send_message", Map.of("to", "alice")).isSuccess());
    }

    @Test
    @DisplayName("check_inbox 空 → 'Inbox empty.'")
    void check_inbox_empty() {
        ToolResult r = call("check_inbox", Map.of());
        assertTrue(r.isSuccess());
        assertEquals("Inbox empty.", r.getOutput());
    }

    @Test
    @DisplayName("check_inbox 有消息 → 多行格式 + drain 邮箱")
    void check_inbox_with_messages_drains() {
        bus.send("alice", "lead", "schema done", "result");
        bus.send("bob", "lead", "client ready", "result");

        ToolResult r = call("check_inbox", Map.of());
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("2 message(s)"));
        assertTrue(r.getOutput().contains("alice"));
        assertTrue(r.getOutput().contains("schema done"));
        assertTrue(r.getOutput().contains("bob"));

        // 第二次 check 应返空(消费式)
        assertEquals("Inbox empty.", call("check_inbox", Map.of()).getOutput());
    }

    @Test
    @DisplayName("未知工具名 → 'Unknown tool: <name>'")
    void unknown_tool() {
        ToolResult r = call("not_a_tool", Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("Unknown tool"));
    }
}
