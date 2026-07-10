package com.xilidou.jooj.channel;

import com.xilidou.jooj.agent.AgentLoopHarness;
import com.xilidou.jooj.hook.HookManager;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import com.xilidou.jooj.session.AgentLockProvider;
import com.xilidou.jooj.session.Session;
import com.xilidou.jooj.session.SessionService;
import com.xilidou.jooj.slashcmd.SlashCommandRegistry;
import com.xilidou.jooj.tool.ExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * InboundDispatcher —— 所有入站入口(Channel / Web / 未来其他)的统一 pipeline。
 *
 * <h3>调用点</h3>
 *
 * <ul>
 *   <li>{@link #dispatch(ChannelMessage)} —— push-style channel(微信/Discord)fire-and-forget</li>
 *   <li>{@link #dispatchSync(DispatchRequest)} —— 请求/响应式(Web REST)拿到结构化结果自己映射</li>
 *   <li>{@link #history(String)} / {@link #clearHistory(String)} —— 会话读写,共用同一把锁</li>
 * </ul>
 *
 * <h3>Pipeline 步骤(所有入口共用)</h3>
 *
 * <ol>
 *   <li>resolveSessionId + exists 校验(可选 autoCreate)</li>
 *   <li>Slash 命令短路(不喂 LLM、不写 history)</li>
 *   <li>UserPromptHook 拦截(跟 CLI REPL 对齐)</li>
 *   <li>抢 per-session lock(不同 session 并行,同 session 互斥)</li>
 *   <li>调 {@link AgentLoopHarness#processOneQuery} 走完整 LLM 流程</li>
 *   <li>从 history 抽 last assistant text + 本轮 tool 调用名</li>
 * </ol>
 *
 * <p>Channel 入站是 fire-and-forget,pipeline 结果通过 {@link #sendReply} 回写;
 * Web 是同步 RPC,pipeline 结果作为 {@link DispatchResult} 返给 controller,由 controller
 * 映射到 HTTP status。
 */
@Component
@Slf4j
public class InboundDispatcher implements ChannelDeliverer {

    private final AgentLoopHarness harness;
    private final SessionService sessionService;
    private final AgentLockProvider lockProvider;
    /**
     * s21 Demo 25 副作用 v4:slash 命令路由 —— IM 用户在微信里发 {@code /clear} / {@code /help}
     * 也走纯客户端命令,不喂 LLM。null 安全(SlashCommandRegistry 没装时 dispatcher 仍能跑)。
     */
    private final SlashCommandRegistry slashCommands;
    /**
     * s21 Demo 27 review:UserPromptHook 路由 —— 入站文本必须经过 hooks.triggerUserPrompt
     * 才能进 LLM(跟 CLI REPL 同步)。null 安全(HookManager 没装时不拦,等价旧行为)。
     */
    private final HookManager hooks;
    private final Map<String, MessageChannel> channels = new HashMap<>();

    public InboundDispatcher(AgentLoopHarness harness,
                             SessionService sessionService,
                             AgentLockProvider lockProvider,
                             SlashCommandRegistry slashCommands,
                             HookManager hooks) {
        this.harness = harness;
        this.sessionService = sessionService;
        this.lockProvider = lockProvider;
        this.slashCommands = slashCommands;
        this.hooks = hooks;
    }

    /** Channel 启动时主动注册自己 —— 出站要靠这张表。 */
    public synchronized void registerChannel(MessageChannel channel) {
        channels.put(channel.name(), channel);
        log.info("[Channel] registered: {}", channel.name());
    }

    public synchronized void unregisterChannel(String name) {
        channels.remove(name);
        log.info("[Channel] unregistered: {}", name);
    }

    // ─────────────────────────────────────────────────────────────
    //  统一入口 —— 结构化结果
    // ─────────────────────────────────────────────────────────────

    /** 分派结果状态。Web 层据此映射 HTTP status;Channel 层只关心 reply 有没有。 */
    public enum Status {
        /** 走完 LLM,reply 有值(可能为空串,取决于 LLM 输出)。 */
        OK,
        /** slash 命令处理完,reply = 命令输出。 */
        SLASH_HANDLED,
        /** UserPromptHook 拦截,reply = 已加前缀的阻断消息,errorMessage = 原因。 */
        HOOK_BLOCKED,
        /** 请求的 session 不存在(且未启用自动创建)。 */
        SESSION_NOT_FOUND,
        /** per-session lock 抢占失败(同 session 已有请求在跑)。 */
        SESSION_BUSY,
        /** 输入非法(query 空白等)。 */
        BAD_REQUEST,
        /** processOneQuery 抛异常。 */
        AGENT_FAILED
    }

    /**
     * 分派请求 —— 抽掉 channel / peer 具体细节,只保留 pipeline 需要的语义。
     *
     * @param sessionId       目标 session(null/空白由 pipeline resolve 到 DEFAULT_ID)
     * @param query           用户输入原文
     * @param autoCreate      session 不存在时是否自动创建(channel=true, web=false)
     * @param autoCreateTitle 自动创建时的 title(仅 autoCreate=true 时用,null 则用 sessionId)
     * @param hint            deliveryHint(仅 channel 有,web 传 null)
     */
    public record DispatchRequest(
            String sessionId,
            String query,
            boolean autoCreate,
            String autoCreateTitle,
            ExecutionContext.DeliveryHint hint
    ) {}

    /**
     * 分派结果 —— 结构化终态,调用方按需映射(HTTP status / channel 回写 / 日志)。
     *
     * @param status            终态
     * @param reply             对用户可见的回复文本(OK / SLASH_HANDLED / HOOK_BLOCKED 有值)
     * @param errorMessage      错误说明(非成功态用;HOOK_BLOCKED 时是被阻断的原始原因)
     * @param historySize       当前 history 长度(OK / SLASH_HANDLED / HOOK_BLOCKED 有效)
     * @param toolCallsThisTurn 本轮 turn 调用的工具名(仅 status=OK 有意义)
     */
    public record DispatchResult(
            Status status,
            String reply,
            String errorMessage,
            int historySize,
            List<String> toolCallsThisTurn
    ) {
        public static DispatchResult error(Status s, String msg) {
            return new DispatchResult(s, null, msg, 0, List.of());
        }
    }

    /**
     * 同步分派 —— Web REST / 未来 sync RPC 用。跑完 pipeline 拿结构化结果,
     * 调用方自己映射到自己的响应格式(HTTP status / RPC error code / ...)。
     */
    public DispatchResult dispatchSync(DispatchRequest req) {
        if (req == null || req.query() == null || req.query().isBlank()) {
            return DispatchResult.error(Status.BAD_REQUEST, "query must not be blank");
        }
        String sessionId = resolveSessionId(req.sessionId());

        if (!sessionService.exists(sessionId)) {
            if (req.autoCreate()) {
                String title = req.autoCreateTitle() != null ? req.autoCreateTitle() : sessionId;
                sessionService.createWithId(sessionId, title);
                log.info("[Dispatch] auto-created session {} (title={})", sessionId, title);
            } else {
                return DispatchResult.error(Status.SESSION_NOT_FOUND, "session not found: " + sessionId);
            }
        }

        // 1) Slash 命令短路 —— 不进 LLM、不抢 session lock、不写 history。
        if (slashCommands != null && slashCommands.isCommand(req.query())) {
            String reply = slashCommands.dispatch(req.query(), sessionId);
            int size = harness.getHistory(sessionId).size();
            return new DispatchResult(Status.SLASH_HANDLED, reply, null, size, List.of());
        }

        // 2) UserPromptHook 必须在 LLM 拿到 query 之前执行 —— 跟 CLI REPL 行为对齐。
        if (hooks != null) {
            Optional<String> blocked = hooks.triggerUserPrompt(req.query());
            if (blocked.isPresent()) {
                log.info("[Dispatch] user prompt blocked by hook (session={}): {}",
                        sessionId, blocked.get());
                return new DispatchResult(
                        Status.HOOK_BLOCKED,
                        "⛔ Prompt blocked: " + blocked.get(),
                        blocked.get(),
                        harness.getHistory(sessionId).size(),
                        List.of());
            }
        }

        // 3) 抢 per-session lock。
        ReentrantLock lock = lockProvider.lockFor(sessionId);
        if (!lock.tryLock()) {
            return DispatchResult.error(Status.SESSION_BUSY,
                    "Session busy (another request is running for this session). Please retry.");
        }
        try {
            int historyBefore = harness.getHistory(sessionId).size();
            try {
                if (req.hint() != null) {
                    harness.processOneQuery(sessionId, req.query(), req.hint());
                } else {
                    harness.processOneQuery(sessionId, req.query());
                }
            } catch (Exception e) {
                log.error("[Dispatch] processOneQuery failed (session={})", sessionId, e);
                return DispatchResult.error(Status.AGENT_FAILED,
                        "Agent failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            List<MessageParam> history = harness.getHistory(sessionId);
            String reply = lastAssistantText(history, historyBefore);
            List<String> toolCalls = collectToolCallsSince(history, historyBefore);
            return new DispatchResult(Status.OK, reply, null, history.size(), toolCalls);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Channel 入站(fire-and-forget)—— 薄化为:适配 → dispatchSync → 回写 channel。
     *
     * <p>**调用方应该在自己的 worker 线程跑**(每个 channel 自己开线程池),这里会阻塞
     * 数秒到数分钟(LLM 慢 + 工具调用)。dispatcher 不持线程池。
     */
    public void dispatch(ChannelMessage msg) {
        String sessionId = sessionIdFor(msg);
        String title = msg.channel() + ":" + (msg.peerName() != null ? msg.peerName() : msg.peerId());
        ExecutionContext.DeliveryHint hint =
                new ExecutionContext.DeliveryHint(msg.channel(), msg.peerId());

        DispatchResult r = dispatchSync(new DispatchRequest(
                sessionId, msg.text(), true, title, hint));

        switch (r.status()) {
            case SESSION_BUSY -> log.warn("[Channel:{}] session {} busy, dropping inbound from peer={}",
                    msg.channel(), sessionId, msg.peerId());
            case AGENT_FAILED -> {
                // 已在 dispatchSync 内 log.error;channel 侧静默丢弃(不回写异常给 peer)
            }
            case BAD_REQUEST -> log.debug("[Channel:{}] bad request from peer={}: {}",
                    msg.channel(), msg.peerId(), r.errorMessage());
            case SESSION_NOT_FOUND -> log.warn("[Channel:{}] session not found (unexpected, autoCreate=true): {}",
                    msg.channel(), r.errorMessage());
            case OK, SLASH_HANDLED, HOOK_BLOCKED -> {
                if (r.reply() != null && !r.reply().isBlank()) {
                    sendReply(msg, r.reply());
                } else {
                    log.info("[Channel:{}] no assistant text to reply (peer={})",
                            msg.channel(), msg.peerId());
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  history / clear —— 供 Web 层复用,共享 per-session lock 语义
    // ─────────────────────────────────────────────────────────────

    /** 读取指定 session 的对话历史(null/空 → DEFAULT_ID 兜底)。 */
    public List<MessageParam> history(String sessionId) {
        return harness.getHistory(resolveSessionId(sessionId));
    }

    /**
     * 清空指定 session 的对话历史。
     *
     * @return true = 清空成功;false = session busy(有请求在跑)。
     */
    public boolean clearHistory(String sessionId) {
        String sid = resolveSessionId(sessionId);
        ReentrantLock lock = lockProvider.lockFor(sid);
        if (!lock.tryLock()) return false;
        try {
            harness.clearHistory(sid);
            return true;
        } finally {
            lock.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  ChannelDeliverer(harness / cron 主动 outbound)
    // ─────────────────────────────────────────────────────────────

    @Override
    public boolean deliver(String channel, String peerId, String text) {
        if (channel == null || peerId == null || text == null || text.isBlank()) {
            log.debug("[Channel] deliver skipped: missing args (channel={}, peerId={}, text={})",
                    channel, peerId, text == null ? "null" : "len=" + text.length());
            return false;
        }
        MessageChannel ch = channels.get(channel);
        if (ch == null) {
            log.warn("[Channel:{}] not registered, skipping deliver to peer={}", channel, peerId);
            return false;
        }
        try {
            ch.sendOutbound(peerId, text);
            log.info("[Channel:{}] delivered to peer={} ({} chars)", channel, peerId, text.length());
            return true;
        } catch (Exception e) {
            log.warn("[Channel:{}] sendOutbound failed (peer={}): {}", channel, peerId, e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  内部辅助
    // ─────────────────────────────────────────────────────────────

    private void sendReply(ChannelMessage msg, String reply) {
        if (reply == null || reply.isBlank()) return;
        MessageChannel ch = channels.get(msg.channel());
        if (ch == null) {
            log.warn("[Channel:{}] not registered, dropping reply to peer={}",
                    msg.channel(), msg.peerId());
            return;
        }
        try {
            ch.sendOutbound(msg.peerId(), reply);
        } catch (Exception e) {
            log.warn("[Channel:{}] sendOutbound failed (peer={}): {}",
                    msg.channel(), msg.peerId(), e.getMessage());
        }
    }

    /**
     * 构造 session id —— 严格匹配 SessionStore 的 {@code [a-zA-Z0-9_-]+} 字符集(防路径注入)。
     */
    static String sessionIdFor(ChannelMessage msg) {
        String safePeer = msg.peerId().replaceAll("[^a-zA-Z0-9_-]", "_");
        return "chat_" + msg.channel() + "_" + safePeer;
    }

    /** 空白 sessionId → {@link Session#DEFAULT_ID} 兜底。 */
    private static String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Session.DEFAULT_ID;
        return sessionId.trim();
    }

    /** 从 sinceIndex 之后的 history 里找最后一条 assistant 文本。跳过 tool_use / thinking。 */
    private static String lastAssistantText(List<MessageParam> history, int sinceIndex) {
        for (int i = history.size() - 1; i >= Math.max(0, sinceIndex); i--) {
            MessageParam m = history.get(i);
            if (!"assistant".equals(m.getRole())) continue;
            Object c = m.getContent();
            if (c instanceof String s && !s.isBlank()) return s;
            if (c instanceof List<?> blocks) {
                StringBuilder sb = new StringBuilder();
                for (Object b : blocks) {
                    if (b instanceof TextBlock tb && tb.getText() != null) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(tb.getText());
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }
        }
        return "";
    }

    /** 收集本次 turn 在 history 增量里出现的工具名(去重保序)。 */
    private static List<String> collectToolCallsSince(List<MessageParam> history, int sinceIdx) {
        List<String> out = new ArrayList<>();
        for (int i = Math.max(0, sinceIdx); i < history.size(); i++) {
            MessageParam m = history.get(i);
            if (!"assistant".equals(m.getRole())) continue;
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object b : blocks) {
                if (b instanceof ToolUseBlock tu && !out.contains(tu.getName())) {
                    out.add(tu.getName());
                }
            }
        }
        return out;
    }
}