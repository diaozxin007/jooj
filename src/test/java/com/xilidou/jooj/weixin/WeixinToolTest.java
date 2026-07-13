package com.xilidou.jooj.weixin;

import cn.langchat.openclaw.weixin.OpenClawWeixinSdk;
import cn.langchat.openclaw.weixin.model.WeixinAccount;
import cn.langchat.openclaw.weixin.storage.FileAccountStore;
import com.xilidou.jooj.tool.ExecutionContext;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WeixinTool 单元测试 —— 不启动 Spring,纯 mock SDK 验证工具路由 + 错误处理。
 *
 * <p>三类场景:已登录走通、未登录给清晰提示、参数校验。
 *
 * <p>s22 架构审查(2026-07-13):weixin 工具族现在**严格 session 隔离** ——
 * 只有 {@code ExecutionContext.deliveryHint().channel() == "weixin"} 且
 * {@code peer} 参数等于 ctx.peerId 时才放行。所有测试用例都在 {@code chat_weixin_test-peer}
 * ctx 里调,并覆盖 3 类隔离拒绝场景(Web/CLI 无 hint、旧签名、跨 peer)。
 */
class WeixinToolTest {

    private OpenClawWeixinSdk sdk;
    private FileAccountStore accounts;
    private WeixinProperties props;
    private WeixinAccountState accountState;
    private WeixinTool tool;

    /**
     * 测试专用 ctx —— 模拟"微信 peer test-peer 发的消息触发了 turn"。
     * 大多数用例用这个,允许 weixin 工具调用。
     */
    private ExecutionContext weixinCtx(String peerId) {
        return ExecutionContext.leadInChannel(
                "chat_weixin_" + peerId, "weixin", peerId);
    }

    @BeforeEach
    void setUp() {
        sdk = mock(OpenClawWeixinSdk.class);
        accounts = mock(FileAccountStore.class);
        when(sdk.accounts()).thenReturn(accounts);

        props = new WeixinProperties();
        props.setBotAgent("jooj-test");

        // s21 Demo 16.5: account state 从 props 拆出来,mock 它返"test-acc"
        accountState = mock(WeixinAccountState.class);
        when(accountState.getActiveAccountId()).thenReturn("test-acc");

        tool = new WeixinTool(sdk, props, accountState);
    }

    @Test
    @DisplayName("getTools:暴露 3 个 tool name,跟 covers() 一致")
    void exposes_three_tools() {
        var defs = tool.getTools();
        assertEquals(3, defs.size());
        for (var d : defs) {
            assertTrue(WeixinTool.covers(d.getName()),
                    "covers() 应该认识: " + d.getName());
        }
    }

    @Test
    @DisplayName("status:未登录 → logged_in=false + hint 引导扫码")
    void status_when_not_logged_in() {
        when(accounts.load("test-acc")).thenReturn(Optional.empty());

        ToolResult r = tool.execute(
                new ToolCall("weixin_status", Map.of()),
                weixinCtx("filehelper"));

        assertTrue(r.isSuccess());
        String out = r.getOutput();
        assertTrue(out.contains("\"logged_in\":false"), "应有 logged_in:false: " + out);
        assertTrue(out.contains("/api/weixin/qr"), "应有扫码 endpoint 提示: " + out);
    }

    @Test
    @DisplayName("status:已登录 → logged_in=true + 带 userId")
    void status_when_logged_in() {
        WeixinAccount acc = new WeixinAccount(
                "test-acc", "tok-xyz", "https://ilinkai.weixin.qq.com",
                "wxid_user_001", "2026-06-29T10:00:00Z");
        when(accounts.load("test-acc")).thenReturn(Optional.of(acc));

        ToolResult r = tool.execute(
                new ToolCall("weixin_status", Map.of()),
                weixinCtx("filehelper"));

        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("\"logged_in\":true"));
        assertTrue(r.getOutput().contains("wxid_user_001"));
    }

    @Test
    @DisplayName("send_text:未登录 → 失败且不调 SDK 发送")
    void send_text_blocked_when_not_logged_in() {
        when(accounts.load("test-acc")).thenReturn(Optional.empty());

        ToolResult r = tool.execute(
                new ToolCall("weixin_send_text",
                        Map.of("peer", "filehelper", "text", "hi")),
                weixinCtx("filehelper"));

        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("not logged in"));
        verify(sdk, never()).sendText(any(), any(), any());
    }

    @Test
    @DisplayName("send_text:已登录 → 调 SDK + 返回 msgId")
    void send_text_dispatches_to_sdk() {
        WeixinAccount acc = new WeixinAccount(
                "test-acc", "tok", "url", "uid", "ts");
        when(accounts.load("test-acc")).thenReturn(Optional.of(acc));
        when(sdk.sendText(eq("test-acc"), eq("filehelper"), eq("hello")))
                .thenReturn("msg_001");

        ToolResult r = tool.execute(
                new ToolCall("weixin_send_text",
                        Map.of("peer", "filehelper", "text", "hello")),
                weixinCtx("filehelper"));

        assertTrue(r.isSuccess(), "失败原因: " + r.getOutput());
        assertTrue(r.getOutput().contains("msg_001"));
    }

    @Test
    @DisplayName("send_text:缺 peer/text 参数 → 不调 SDK")
    void send_text_validates_args() {
        ExecutionContext ctx = weixinCtx("filehelper");
        ToolResult missingPeer = tool.execute(
                new ToolCall("weixin_send_text", Map.of("text", "hi")), ctx);
        assertFalse(missingPeer.isSuccess());
        assertTrue(missingPeer.getOutput().contains("peer"));

        ToolResult missingText = tool.execute(
                new ToolCall("weixin_send_text", Map.of("peer", "filehelper")), ctx);
        assertFalse(missingText.isSuccess());
        assertTrue(missingText.getOutput().contains("text"));

        ToolResult blankText = tool.execute(
                new ToolCall("weixin_send_text",
                        Map.of("peer", "filehelper", "text", "   ")), ctx);
        assertFalse(blankText.isSuccess());
        assertTrue(blankText.getOutput().contains("blank"));

        verify(sdk, never()).sendText(any(), any(), any());
    }

    @Test
    @DisplayName("send_text:SDK 抛异常 → 工具捕获返回 failed,不向上抛")
    void send_text_handles_sdk_exception() {
        WeixinAccount acc = new WeixinAccount("test-acc", "tok", "url", "uid", "ts");
        when(accounts.load("test-acc")).thenReturn(Optional.of(acc));
        when(sdk.sendText(any(), any(), any()))
                .thenThrow(new RuntimeException("server returned ret=42"));

        ToolResult r = tool.execute(
                new ToolCall("weixin_send_text",
                        Map.of("peer", "filehelper", "text", "hi")),
                weixinCtx("filehelper"));

        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("ret=42"),
                "应包含 SDK 抛的具体错误: " + r.getOutput());
    }

    @Test
    @DisplayName("list_peers:未登录 → 失败提示")
    void list_peers_when_not_logged_in() {
        when(accounts.load("test-acc")).thenReturn(Optional.empty());

        ToolResult r = tool.execute(
                new ToolCall("weixin_list_peers", Map.of()),
                weixinCtx("filehelper"));

        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("not logged in"));
    }

    @Test
    @DisplayName("list_peers:已登录但无 peer → 友好提示")
    void list_peers_empty() {
        WeixinAccount acc = new WeixinAccount("test-acc", "tok", "url", "uid", "ts");
        when(accounts.load("test-acc")).thenReturn(Optional.of(acc));
        when(sdk.listKnownPeers("test-acc")).thenReturn(Set.of());

        ToolResult r = tool.execute(
                new ToolCall("weixin_list_peers", Map.of()),
                weixinCtx("filehelper"));

        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("no known peers"));
    }

    @Test
    @DisplayName("list_peers:有 peer → 列出")
    void list_peers_with_data() {
        WeixinAccount acc = new WeixinAccount("test-acc", "tok", "url", "uid", "ts");
        when(accounts.load("test-acc")).thenReturn(Optional.of(acc));
        when(sdk.listKnownPeers("test-acc"))
                .thenReturn(Set.of("filehelper", "wxid_a"));

        ToolResult r = tool.execute(
                new ToolCall("weixin_list_peers", Map.of()),
                weixinCtx("filehelper"));

        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("filehelper"));
        assertTrue(r.getOutput().contains("wxid_a"));
    }

    @Test
    @DisplayName("未知 tool name → 拒绝")
    void unknown_tool_rejected() {
        ToolResult r = tool.execute(
                new ToolCall("weixin_unknown", Map.of()),
                weixinCtx("filehelper"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("Unknown"));
    }

    // ─────────────────────────────────────────────────────────────
    //  s22 架构审查:session 隔离新用例
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("session 隔离:非 weixin channel ctx(如 web/cli)→ 拒绝所有 weixin 工具")
    void non_weixin_channel_context_refused() {
        WeixinAccount acc = new WeixinAccount("test-acc", "tok", "url", "uid", "ts");
        when(accounts.load("test-acc")).thenReturn(Optional.of(acc));

        // ExecutionContext.leadInSession = 没有 deliveryHint(web/cli 场景)
        ExecutionContext webCtx = ExecutionContext.leadInSession("default");

        for (String toolName : new String[]{"weixin_status", "weixin_list_peers", "weixin_send_text"}) {
            Map<String, Object> args = "weixin_send_text".equals(toolName)
                    ? Map.of("peer", "filehelper", "text", "hi")
                    : Map.of();
            ToolResult r = tool.execute(new ToolCall(toolName, args), webCtx);
            assertFalse(r.isSuccess(), toolName + " 应在非 weixin ctx 下被拒");
            assertTrue(r.getOutput().contains("weixin channel session"),
                    toolName + " 拒绝理由应含 'weixin channel session': " + r.getOutput());
        }
        // 关键:即使 send_text 被拒,SDK 也没被调 —— 防止边界穿透
        verify(sdk, never()).sendText(any(), any(), any());
    }

    @Test
    @DisplayName("session 隔离:其他 channel(如 discord)ctx → 拒绝")
    void non_weixin_channel_hint_refused() {
        ExecutionContext discordCtx = ExecutionContext.leadInChannel(
                "chat_discord_user1", "discord", "user1");

        ToolResult r = tool.execute(
                new ToolCall("weixin_status", Map.of()), discordCtx);
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("weixin channel session"));
    }

    @Test
    @DisplayName("session 隔离:旧签名 execute(call) 直接拒绝(防绕过 ctx 检查)")
    void legacy_signature_refused() {
        ToolResult r = tool.execute(new ToolCall("weixin_status", Map.of()));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("session isolation"),
                "旧签名拒绝理由应提到隔离约束: " + r.getOutput());
    }

    @Test
    @DisplayName("session 隔离:send_text 跨 peer(session 是 peer-A 但 tool 传 peer-B)→ 拒绝")
    void cross_peer_send_refused() {
        WeixinAccount acc = new WeixinAccount("test-acc", "tok", "url", "uid", "ts");
        when(accounts.load("test-acc")).thenReturn(Optional.of(acc));

        // ctx 是 peer-A 的会话
        ExecutionContext peerA = weixinCtx("peer-A");

        // 但 tool 试图给 peer-B 发
        ToolResult r = tool.execute(
                new ToolCall("weixin_send_text",
                        Map.of("peer", "peer-B", "text", "cross-peer message")),
                peerA);

        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("Cross-peer") || r.getOutput().contains("cross-peer"),
                "拒绝理由应提 cross-peer: " + r.getOutput());
        assertTrue(r.getOutput().contains("peer-A"), "应说明当前 session 关联的 peer");
        assertTrue(r.getOutput().contains("peer-B"), "应说明 tool 试图发的 peer");
        verify(sdk, never()).sendText(any(), any(), any());
    }
}
