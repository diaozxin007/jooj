package com.xilidou.jooj.tool;

import lombok.extern.slf4j.Slf4j;

/**
 * ChannelTool —— 跟外部 channel(微信 / Discord / 飞书 / ...)交互的 Tool 的抽象基类。
 *
 * <h3>解决什么问题</h3>
 *
 * <p>Channel tool(比如 {@code weixin_send_text})有跨 session 泄漏风险:LLM 在
 * 任何 session 里都能调它主动 outbound —— 用户在 web 里聊天,LLM 却把回复推到
 * 某个 weixin peer 的手机上。见 s22 架构审查 Round 2。
 *
 * <p>本抽象基类把 "session 隔离 gate" 抽出来,让所有 channel tool 复用:
 *
 * <ol>
 *   <li><b>Channel gate</b>:{@link #execute(ToolCall, ExecutionContext)} 检查
 *       {@code ctx.deliveryHint().channel() == expectedChannel()},不匹配直接拒</li>
 *   <li><b>Peer 深度防御 helper</b>:{@link #verifyPeer(ToolCall, ExecutionContext, String)}
 *       检查 tool 调用参数里的 peer 匹配 ctx.peerId。子类**自愿调用**——
 *       "send_text" 这种真要 outbound 的必须调,"status"/"list_peers" 这种只读的可以不调</li>
 *   <li><b>旧签名默认拒</b>:{@link #execute(ToolCall)} 直接返错误,防绕过 ctx 检查</li>
 * </ol>
 *
 * <h3>子类需要做的</h3>
 *
 * <p>就 2 件事:
 * <ol>
 *   <li>{@link #expectedChannel()} 声明 "weixin" / "discord" / etc</li>
 *   <li>{@link #executeGated(ToolCall, ExecutionContext)} 实现业务逻辑
 *       —— 到这里已经确定 ctx 是本 channel 的会话</li>
 * </ol>
 *
 * <h3>不做的事</h3>
 *
 * <ul>
 *   <li>不强制 tool name 前缀({@code weixin_*} / {@code discord_*})——
 *       子类自愿命名。工具名跟 channel 关系是文档约定,不是代码约束</li>
 *   <li>不做 "admin 豁免"—— 若管理员需要 debug channel 连接,走 slash 命令或日志</li>
 *   <li>不给 LLM 传"当前 session 是不是 X channel" 的元信息 —— 由 gate 决定,
 *       LLM 只需要"要么能调要么不能" 的二元结果</li>
 * </ul>
 *
 * <h3>典型子类形态</h3>
 *
 * <pre>{@code
 * @Component
 * public class WeixinTool extends ChannelTool {
 *     @Override
 *     public String expectedChannel() { return "weixin"; }
 *
 *     @Override
 *     public ToolResult executeGated(ToolCall call, ExecutionContext ctx) {
 *         return switch (call.getToolName()) {
 *             case "weixin_status" -> doStatus();
 *             case "weixin_list_peers" -> doListPeers();
 *             case "weixin_send_text" -> {
 *                 // 需要 peer 匹配的动作显式调 verifyPeer
 *                 String peer = call.getArguments().get("peer").toString();
 *                 ToolResult crossPeer = verifyPeer(call, ctx, peer);
 *                 if (crossPeer != null) yield crossPeer;
 *                 yield doSendText(call, ctx);
 *             }
 *             default -> new ToolResult(false, "Unknown tool: " + call.getToolName());
 *         };
 *     }
 * }
 * }</pre>
 */
@Slf4j
public abstract class ChannelTool implements Tool {

    /**
     * 声明本 tool 服务的 channel 名 —— 会跟 {@code ExecutionContext.deliveryHint().channel()}
     * 精确匹配。返回值必须是**小写、简短、稳定**(比如 "weixin"、"discord")。
     */
    public abstract String expectedChannel();

    /**
     * 门禁通过后的业务实现。ctx 保证 non-null 且 {@code ctx.deliveryHint().channel()}
     * 等于 {@link #expectedChannel()}。
     */
    protected abstract ToolResult executeGated(ToolCall call, ExecutionContext ctx);

    /**
     * Channel gate —— 只有当 ctx 是本 channel 会话时才 dispatch 到 {@link #executeGated}。
     * 其他所有 session (web / cli / cron / subagent / 其他 channel) 一律拒。
     */
    @Override
    public final ToolResult execute(ToolCall call, ExecutionContext ctx) {
        if (!isThisChannelContext(ctx)) {
            String toolName = call.getToolName();
            log.info("[{}Tool] refused {} — current session is not a {} channel session",
                    expectedChannel(), toolName, expectedChannel());
            return new ToolResult(false,
                    "This tool (" + toolName + ") can only be called from a " + expectedChannel() +
                    " channel session (i.e. a conversation initiated by an incoming " +
                    expectedChannel() + " message). The current session is not linked to any " +
                    expectedChannel() + " peer, so " + expectedChannel() +
                    " tools are unavailable to prevent cross-session outbound.");
        }
        try {
            return executeGated(call, ctx);
        } catch (Exception e) {
            log.warn("[{}Tool] {} failed", expectedChannel(), call.getToolName(), e);
            return new ToolResult(false,
                    "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 旧签名兜底:harness 走新签名,ctx 检查在那里。旧签名调用点(测试 / 手工构造)
     * 默认拒绝所有动作,避免"绕过 ctx 检查"路径。
     */
    @Override
    public final ToolResult execute(ToolCall call) {
        log.warn("[{}Tool] {} invoked via legacy signature (no ExecutionContext) — refusing",
                expectedChannel(), call.getToolName());
        return new ToolResult(false,
                expectedChannel() + " tools require ExecutionContext (session isolation). " +
                "This tool is only callable from a chat_" + expectedChannel() + "_* session.");
    }

    // ── 供子类使用的 helper ────────────────────────────────────

    /**
     * 深度防御:检查 tool 参数里的 peer 匹配当前 session 关联的 peer。
     *
     * <p>Channel gate 保证了 "本 session 是本 channel 的",但 LLM 仍然可能试图
     * "在 peer-A 的会话里给 peer-B 发消息" —— 这也是隔离违规。子类的"发消息"类
     * 动作应该显式调这个 helper。
     *
     * @param call     tool 调用(参数里应有 "peer")
     * @param ctx      Channel gate 已确保匹配的 ctx
     * @param peerArg  tool 参数里的 peer 值(子类先取出来,基类不假设参数名)
     * @return null 表示允许(peer 匹配);非 null 表示拒绝(caller 直接返回给 LLM)
     */
    protected ToolResult verifyPeer(ToolCall call, ExecutionContext ctx, String peerArg) {
        String expectedPeer = ctx.deliveryHint().peerId();
        if (expectedPeer != null && expectedPeer.equals(peerArg)) {
            return null;  // 通过
        }
        log.info("[{}Tool] refused {}: session peer={} but tool arg peer={}",
                expectedChannel(), call.getToolName(), expectedPeer, peerArg);
        return new ToolResult(false,
                "Cross-peer outbound refused: this session is linked to peer '" + expectedPeer +
                "', but the tool was called with peer='" + peerArg + "'. " +
                "You can only reply to the peer whose message initiated this conversation.");
    }

    /** 判定当前 ExecutionContext 是不是本 channel 发起的对话。 */
    private boolean isThisChannelContext(ExecutionContext ctx) {
        if (ctx == null) return false;
        ExecutionContext.DeliveryHint hint = ctx.deliveryHint();
        return hint != null && expectedChannel().equals(hint.channel());
    }
}
