package com.xilidou.jooj.weixin;

import cn.langchat.openclaw.weixin.OpenClawWeixinSdk;
import cn.langchat.openclaw.weixin.model.WeixinAccount;
import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.tool.ExecutionContext;
import com.xilidou.jooj.tool.Tool;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * WeixinTool —— 把 OpenClaw 微信 SDK 包成 LLM 可调工具。
 *
 * <h3>暴露给 LLM 的动作</h3>
 *
 * <ul>
 *   <li>{@code weixin_status} — 检查当前是否已登录(LLM 决定要不要发消息前先调一下)</li>
 *   <li>{@code weixin_list_peers} — 列出已知 peer(已收到过消息的会话)</li>
 *   <li>{@code weixin_send_text} — 给指定 peer 发文本消息</li>
 * </ul>
 *
 * <h3>不暴露的动作</h3>
 *
 * <ul>
 *   <li>QR 登录 — 需要人肉扫码,LLM 调没意义,走 REST {@code /api/weixin/qr/*}</li>
 *   <li>get_updates — 长轮询(默认 30s+),同步工具调用会让 LLM 卡住;真要订阅
 *       消息走 WeixinLongPollMonitor + 后台线程,把消息当事件入 history(超出当前范围)</li>
 *   <li>媒体发送 — 第一版只支持文本;媒体涉及 CDN 上传 + 加密,后续按需补</li>
 * </ul>
 *
 * <h3>启用</h3>
 *
 * <p>跟 {@link WeixinConfiguration} 共享 {@code jooj.weixin.enabled=true}。disabled 时
 * 这个 bean 不存在,ToolRegistry 自然不会注册 → LLM SYSTEM 提示也不会列。
 */
@Component
@ConditionalOnProperty(prefix = "jooj.weixin", name = "enabled", havingValue = "true")
@Slf4j
public class WeixinTool implements Tool {

    private static final Set<String> TOOL_NAMES = Set.of(
            "weixin_status", "weixin_list_peers", "weixin_send_text");

    private final OpenClawWeixinSdk sdk;
    private final WeixinProperties props;
    private final WeixinAccountState accountState;

    public WeixinTool(OpenClawWeixinSdk sdk, WeixinProperties props, WeixinAccountState accountState) {
        this.sdk = sdk;
        this.props = props;
        this.accountState = accountState;
    }

    @Override
    public String getName() {
        return "weixin";
    }

    @Override
    public String getDescription() {
        return "Send Weixin messages and inspect Weixin account state.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        Map<String, Object> emptyProps = Map.of();

        Map<String, Object> sendProps = new java.util.LinkedHashMap<>();
        sendProps.put("peer", Map.of(
                "type", "string",
                "description", "Peer id (Weixin contact id, group id, or 'filehelper')"));
        sendProps.put("text", Map.of(
                "type", "string",
                "description", "Plain text content to send"));

        return List.of(
                new ToolDefinition("weixin_status",
                        "Check whether a Weixin account is logged in. Call this before " +
                                "send_text if uncertain. Returns logged_in=true/false and accountId.",
                        InputSchema.object(emptyProps)),
                new ToolDefinition("weixin_list_peers",
                        "List known Weixin peer ids (contacts/groups already in conversation). " +
                                "Use to find a valid peer before send_text. May be empty if no " +
                                "messages received yet.",
                        InputSchema.object(emptyProps)),
                new ToolDefinition("weixin_send_text",
                        "Send a plain-text Weixin message to a peer. Returns a server-assigned " +
                                "message id on success. Requires the account to be logged in " +
                                "(check with weixin_status first if unsure).",
                        InputSchema.object(sendProps, "peer", "text"))
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        // 兼容旧签名调用点(测试 / 手工构造)—— 但生产 harness 走新签名,ctx 检查在那里。
        // 旧签名场景默认拒绝所有动作,避免"绕过 ctx 检查"路径。
        log.warn("[Weixin] tool {} invoked via legacy signature (no ExecutionContext) — refusing",
                call.getToolName());
        return new ToolResult(false,
                "weixin tools require ExecutionContext (session isolation). " +
                "This tool is only callable from a chat_weixin_* session.");
    }

    /**
     * s22 架构审查(2026-07-13):严格 session 隔离 —— weixin 工具族只能在
     * "微信触发进来的 session" 里被 LLM 调用。web / CLI / cron / subagent 场景直接拒。
     *
     * <h3>为什么</h3>
     *
     * <p>报告的实际 bug:web-default session 里 LLM 调 {@code weixin_send_text} 主动
     * outbound 给某个 peer,导致 web 用户敲的消息回复被推到该 peer 的手机上。
     * 根因不是 sessionId 混淆,是**权限边界**:LLM 在任何 session 都能调 weixin 工具。
     *
     * <h3>判定</h3>
     *
     * <p>只放行 {@code ctx.deliveryHint().channel() == "weixin"} 的调用。其他一律
     * 返 ToolResult(false, ...),LLM 会拿到明确的拒绝理由,不会静默失败。
     *
     * <h3>不做的事</h3>
     *
     * <ul>
     *   <li>不做 admin 豁免 —— 若 CLI/web 管理员需要 debug weixin 连接,走 slash 命令或日志</li>
     *   <li>不豁免 {@code weixin_status}(只读)—— 语义一致优先,跨 session 不该看到别的 channel 状态</li>
     *   <li>不给 LLM 传"当前 session 是不是 weixin channel" 的元信息 —— 由本 gate 决定,
     *       LLM 只需要"要么能调要么不能" 的二元结果</li>
     * </ul>
     */
    @Override
    public ToolResult execute(ToolCall call, ExecutionContext ctx) {
        if (!isWeixinChannelContext(ctx)) {
            String toolName = call.getToolName();
            log.info("[Weixin] refused {} — current session is not a weixin channel session",
                    toolName);
            return new ToolResult(false,
                    "This tool (" + toolName + ") can only be called from a weixin channel " +
                    "session (i.e. a conversation initiated by an incoming weixin message). " +
                    "The current session is not linked to any weixin peer, so weixin tools " +
                    "are unavailable to prevent cross-session outbound.");
        }
        try {
            return switch (call.getToolName()) {
                case "weixin_status" -> doStatus();
                case "weixin_list_peers" -> doListPeers();
                case "weixin_send_text" -> doSendText(call, ctx);
                default -> new ToolResult(false, "Unknown tool: " + call.getToolName());
            };
        } catch (Exception e) {
            log.warn("[Weixin] tool {} failed", call.getToolName(), e);
            return new ToolResult(false, "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 判定当前 ExecutionContext 是不是"微信 channel 发起的对话"。 */
    private static boolean isWeixinChannelContext(ExecutionContext ctx) {
        if (ctx == null) return false;
        ExecutionContext.DeliveryHint hint = ctx.deliveryHint();
        return hint != null && "weixin".equals(hint.channel());
    }

    private ToolResult doStatus() {
        String acc = accountState.getActiveAccountId();
        Optional<WeixinAccount> opt = sdk.accounts().load(acc);
        if (opt.isEmpty()) {
            return new ToolResult(true,
                    "{\"logged_in\":false,\"accountId\":\"" + acc + "\"," +
                            "\"hint\":\"Not logged in. User must scan QR code via /api/weixin/qr endpoint.\"}");
        }
        WeixinAccount a = opt.get();
        return new ToolResult(true,
                "{\"logged_in\":true,\"accountId\":\"" + a.accountId() + "\"," +
                        "\"userId\":\"" + a.userId() + "\",\"savedAt\":\"" + a.savedAt() + "\"}");
    }

    private ToolResult doListPeers() {
        String acc = accountState.getActiveAccountId();
        if (sdk.accounts().load(acc).isEmpty()) {
            return new ToolResult(false,
                    "Account not logged in. Run weixin_status for hint.");
        }
        Set<String> peers = sdk.listKnownPeers(acc);
        if (peers.isEmpty()) {
            return new ToolResult(true,
                    "(no known peers; SDK only learns peers after receiving messages from them)");
        }
        return new ToolResult(true, "peers: " + String.join(", ", peers));
    }

    private ToolResult doSendText(ToolCall call, ExecutionContext ctx) {
        Object peerArg = call.getArguments().get("peer");
        Object textArg = call.getArguments().get("text");
        if (peerArg == null) return new ToolResult(false, "Error: 'peer' is required");
        if (textArg == null) return new ToolResult(false, "Error: 'text' is required");

        String peer = peerArg.toString();
        String text = textArg.toString();
        if (text.isBlank()) return new ToolResult(false, "Error: 'text' must not be blank");

        // s22 架构审查:深度防御 —— peer 必须等于当前 session 关联的 peer。
        // 即使 outer gate isWeixinChannelContext 已通过,也要防 LLM 在 peer-A 的会话里
        // 试图发消息给 peer-B(cross-peer outbound 也是隔离违规)。
        String expectedPeer = ctx.deliveryHint().peerId();
        if (expectedPeer == null || !expectedPeer.equals(peer)) {
            log.info("[Weixin] refused send_text: session peer={} but tool arg peer={}",
                    expectedPeer, peer);
            return new ToolResult(false,
                    "Cross-peer outbound refused: this session is linked to peer '" + expectedPeer +
                    "', but the tool was called with peer='" + peer + "'. " +
                    "You can only reply to the peer whose message initiated this conversation.");
        }

        String acc = accountState.getActiveAccountId();
        if (sdk.accounts().load(acc).isEmpty()) {
            return new ToolResult(false,
                    "Account '" + acc + "' not logged in. " +
                            "User must scan QR code via /api/weixin/qr endpoint first.");
        }

        try {
            String msgId = sdk.sendText(acc, peer, text);
            return new ToolResult(true,
                    "Sent. peer=" + peer + " msgId=" + msgId + " bytes=" + text.length());
        } catch (RuntimeException e) {
            // SDK 把 API 错包装成 RuntimeException(WeixinApiException 等)
            return new ToolResult(false, "Send failed: " + e.getMessage());
        }
    }

    /** 给测试 / 健康检查用 —— 是否覆盖某个 LLM tool 名。 */
    public static boolean covers(String toolName) {
        return TOOL_NAMES.contains(toolName);
    }
}
