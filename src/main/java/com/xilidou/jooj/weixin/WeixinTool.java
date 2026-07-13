package com.xilidou.jooj.weixin;

import cn.langchat.openclaw.weixin.OpenClawWeixinSdk;
import cn.langchat.openclaw.weixin.model.WeixinAccount;
import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.tool.ChannelTool;
import com.xilidou.jooj.tool.ExecutionContext;
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
 *
 * <h3>Session 隔离</h3>
 *
 * <p>继承 {@link ChannelTool},自动获得 3 层 gate(见基类注释):
 * <ol>
 *   <li>Channel gate:只在 {@code ctx.deliveryHint().channel() == "weixin"} 时放行</li>
 *   <li>Peer 深度防御:通过 {@code verifyPeer} helper,{@code weixin_send_text} 里显式调</li>
 *   <li>旧签名默认拒:{@code execute(ToolCall)} 由基类实现拒绝</li>
 * </ol>
 *
 * <p>本类**不再持有**这些通用 gate 代码 —— 全在基类,只留业务(sdk 调用 + 参数校验)。
 */
@Component
@ConditionalOnProperty(prefix = "jooj.weixin", name = "enabled", havingValue = "true")
@Slf4j
public class WeixinTool extends ChannelTool {

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
    public String expectedChannel() {
        return "weixin";
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
    protected ToolResult executeGated(ToolCall call, ExecutionContext ctx) {
        return switch (call.getToolName()) {
            case "weixin_status" -> doStatus();
            case "weixin_list_peers" -> doListPeers();
            case "weixin_send_text" -> doSendText(call, ctx);
            default -> new ToolResult(false, "Unknown tool: " + call.getToolName());
        };
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

        // 深度防御:peer 必须匹配 ctx 的 peerId。基类 helper 处理 —— null 表示通过
        ToolResult crossPeer = verifyPeer(call, ctx, peer);
        if (crossPeer != null) return crossPeer;

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
