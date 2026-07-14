package com.xilidou.jooj.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.subagent.Teammate;
import com.xilidou.jooj.team.MessageBus;
import com.xilidou.jooj.team.TeamConfig;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
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
    private com.xilidou.jooj.team.ProtocolRegistry protocols;

    @BeforeEach
    void setUp() {
        ObjectMapper json = JsonMappers.newMapper();
        bus = new MessageBus(new TeamConfig(tempDir.resolve("mailboxes")), json);
        teammate = Mockito.mock(Teammate.class);
        protocols = new com.xilidou.jooj.team.ProtocolRegistry();
        tool = new TeamTool(teammate, bus, protocols);
    }

    private ToolResult call(String name, Map<String, Object> args) {
        return tool.execute(new ToolCall(name, args));
    }

    @Test
    @DisplayName("getTools 返回 6 个 ToolDefinition(s15 三个 + s16 三个)")
    void exposes_six_tools() {
        List<ToolDefinition> defs = tool.getTools();
        assertEquals(6, defs.size());
        List<String> names = defs.stream().map(ToolDefinition::getName).toList();
        // s15
        assertTrue(names.contains("spawn_teammate"));
        assertTrue(names.contains("send_message"));
        assertTrue(names.contains("check_inbox"));
        // s16
        assertTrue(names.contains("request_shutdown"));
        assertTrue(names.contains("request_plan"));
        assertTrue(names.contains("review_plan"));
    }

    @Test
    @DisplayName("spawn_teammate 正常路径:转发到 Teammate.spawn 并返回 success")
    void spawn_teammate_happy_path() {
        // s22 D-10-D:teammate.spawn 现在是 4-arg (name, role, prompt, parentSid)。
        // 测试路径无 SessionContext.push,TeamTool 内部 SessionContext.current() 返 null。
        when(teammate.spawn(eq("alice"), eq("backend dev"), eq("set up DB"), isNull()))
                .thenReturn("Spawned alice as backend dev");

        ToolResult r = call("spawn_teammate", Map.of(
                "name", "alice",
                "role", "backend dev",
                "prompt", "set up DB"));

        assertTrue(r.isSuccess());
        assertEquals("Spawned alice as backend dev", r.getOutput());
        verify(teammate).spawn(eq("alice"), eq("backend dev"), eq("set up DB"), isNull());
    }

    @Test
    @DisplayName("spawn_teammate Teammate 返 Error 字符串 → success=false")
    void spawn_teammate_returns_error_when_service_fails() {
        when(teammate.spawn(any(), any(), any(), any()))
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

    // ─────────────────────────────────────────────────────────────
    //  s16 新协议工具
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("request_shutdown 注册 protocol state + 发 shutdown_request 到队友")
    void request_shutdown_registers_and_sends() {
        ToolResult r = call("request_shutdown", Map.of("teammate", "alice"));
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().startsWith("Shutdown request sent to alice"));

        // alice 邮箱应有一条 shutdown_request,带 request_id metadata
        var msgs = bus.readInbox("alice");
        assertEquals(1, msgs.size());
        assertEquals("shutdown_request", msgs.get(0).getType());
        String reqId = String.valueOf(msgs.get(0).getMetadata().get("request_id"));
        assertTrue(reqId.startsWith("req_"));

        // protocol registry 里应有 pending state
        assertEquals(1, protocols.size());
        var state = protocols.get(reqId);
        assertEquals(com.xilidou.jooj.team.ProtocolState.PENDING, state.getStatus());
        assertEquals(com.xilidou.jooj.team.ProtocolState.TYPE_SHUTDOWN, state.getType());
        assertEquals("alice", state.getTarget());
    }

    @Test
    @DisplayName("request_shutdown 缺 teammate → 友好错误")
    void request_shutdown_missing_arg() {
        ToolResult r = call("request_shutdown", Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().toLowerCase().contains("teammate"));
    }

    @Test
    @DisplayName("request_plan 发普通指令消息,**不**创建 protocol state(待 teammate 调 submit_plan)")
    void request_plan_sends_message_only() {
        ToolResult r = call("request_plan", Map.of(
                "teammate", "alice", "task", "refactor auth"));
        assertTrue(r.isSuccess());

        var msgs = bus.readInbox("alice");
        assertEquals(1, msgs.size());
        assertEquals("message", msgs.get(0).getType());   // 普通消息,不是协议
        assertTrue(msgs.get(0).getContent().contains("refactor auth"));

        // registry 里**没有** pending state(等 teammate 调 submit_plan 才创建)
        assertEquals(0, protocols.size());
    }

    @Test
    @DisplayName("review_plan 正常:approve → registry pending → approved + 发 plan_approval_response")
    void review_plan_approves_and_responds() {
        // 模拟 teammate 已 submit_plan:registry 里有一条 pending plan_approval
        String reqId = protocols.register(
                com.xilidou.jooj.team.ProtocolState.TYPE_PLAN_APPROVAL,
                "alice", "lead", "refactor auth: drop OAuth, use JWT");

        ToolResult r = call("review_plan", Map.of(
                "request_id", reqId,
                "approve", true,
                "feedback", "go ahead"));
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("approved"));

        // registry 状态应转 approved
        assertEquals(com.xilidou.jooj.team.ProtocolState.APPROVED,
                protocols.get(reqId).getStatus());

        // alice 应收到 plan_approval_response,带 request_id + approve=true + feedback content
        var msgs = bus.readInbox("alice");
        assertEquals(1, msgs.size());
        assertEquals("plan_approval_response", msgs.get(0).getType());
        assertEquals(reqId, msgs.get(0).getMetadata().get("request_id"));
        assertEquals(Boolean.TRUE, msgs.get(0).getMetadata().get("approve"));
        assertEquals("go ahead", msgs.get(0).getContent());
    }

    @Test
    @DisplayName("review_plan reject:状态变 rejected,响应带 approve=false")
    void review_plan_rejects() {
        String reqId = protocols.register(
                com.xilidou.jooj.team.ProtocolState.TYPE_PLAN_APPROVAL,
                "bob", "lead", "drop all DB");

        ToolResult r = call("review_plan", Map.of(
                "request_id", reqId,
                "approve", false));
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("rejected"));
        assertEquals(com.xilidou.jooj.team.ProtocolState.REJECTED,
                protocols.get(reqId).getStatus());

        var msg = bus.readInbox("bob").get(0);
        assertEquals(Boolean.FALSE, msg.getMetadata().get("approve"));
    }

    @Test
    @DisplayName("review_plan 未知 request_id → success=false")
    void review_plan_unknown_id() {
        ToolResult r = call("review_plan", Map.of(
                "request_id", "req_999999", "approve", true));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("not found"));
    }

    @Test
    @DisplayName("review_plan 已 resolved 的请求 → success=false(防 duplicate review)")
    void review_plan_already_resolved() {
        String reqId = protocols.register(
                com.xilidou.jooj.team.ProtocolState.TYPE_PLAN_APPROVAL,
                "alice", "lead", "x");
        // 第一次 review 通过
        call("review_plan", Map.of("request_id", reqId, "approve", true));
        // 第二次 review 应被拒绝
        ToolResult r = call("review_plan", Map.of("request_id", reqId, "approve", false));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("already"));
    }

    @Test
    @DisplayName("check_inbox 自动路由 shutdown_response:registry 状态更新,inbox 不展示给 LLM")
    void check_inbox_routes_shutdown_response() {
        // 准备:Lead 先 register 一条 shutdown 请求
        String reqId = protocols.register(
                com.xilidou.jooj.team.ProtocolState.TYPE_SHUTDOWN, "lead", "alice", "");
        // 模拟 alice 回复 shutdown_response
        bus.send("alice", "lead", "Shutting down.", "shutdown_response",
                Map.of("request_id", reqId, "approve", true));

        ToolResult r = call("check_inbox", Map.of());
        assertTrue(r.isSuccess());
        // 输出应说"只有协议响应,自动路由了"而不是把消息展示给 LLM
        assertTrue(r.getOutput().contains("auto-routed") || r.getOutput().contains("empty"),
                "实际:" + r.getOutput());

        // registry 应转 approved
        assertEquals(com.xilidou.jooj.team.ProtocolState.APPROVED,
                protocols.get(reqId).getStatus());
    }

    @Test
    @DisplayName("check_inbox 协议响应 + 普通消息混合:协议路由,普通展示")
    void check_inbox_routes_mixed_messages() {
        String reqId = protocols.register(
                com.xilidou.jooj.team.ProtocolState.TYPE_SHUTDOWN, "lead", "alice", "");
        bus.send("alice", "lead", "Shutting down.", "shutdown_response",
                Map.of("request_id", reqId, "approve", true));
        bus.send("bob", "lead", "client ready", "result");

        ToolResult r = call("check_inbox", Map.of());
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("bob"));
        assertTrue(r.getOutput().contains("client ready"));
        // shutdown_response 不应展示
        assertFalse(r.getOutput().contains("Shutting down"));
        // registry 仍被更新
        assertEquals(com.xilidou.jooj.team.ProtocolState.APPROVED,
                protocols.get(reqId).getStatus());
    }
}
