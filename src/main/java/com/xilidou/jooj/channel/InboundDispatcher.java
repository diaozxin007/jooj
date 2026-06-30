package com.xilidou.jooj.channel;

import com.xilidou.jooj.agent.AgentLoopHarness;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.session.AgentLockProvider;
import com.xilidou.jooj.session.SessionService;
import com.xilidou.jooj.slashcmd.SlashCommandRegistry;
import com.xilidou.jooj.tool.ExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * InboundDispatcher —— 把 Channel 的入站消息走 LLM 再回写。
 *
 * <h3>流程</h3>
 *
 * <ol>
 *   <li>把 ChannelMessage → sessionId 映射(per-peer:"chat:{channel}:{peerId}")</li>
 *   <li>不存在的 session 自动创建(title = "{channel}:{peerName}")</li>
 *   <li>抢 session lock(跟 Web/CLI 共享同一把锁,避免跟用户当面对话撞车)</li>
 *   <li>调 {@link AgentLoopHarness#processOneQuery} 走完整 LLM 流程</li>
 *   <li>从 history 末尾取 assistant 文本,通过 channel.sendOutbound 发回</li>
 * </ol>
 *
 * <h3>session 路由</h3>
 *
 * <p>{@code chat:{channel}:{peerId}} —— 一 peer 一 session,跟 Demo 9 cron 路由一致风格。
 * 这样跟用户 A 的对话上下文不会串到给用户 B 的回复里。
 *
 * <h3>已知 peer 注册</h3>
 *
 * <p>Channel 在 {@link #registerChannel} 时把自己交给 dispatcher,出站时按 channel 名查回。
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
    private final Map<String, MessageChannel> channels = new HashMap<>();

    public InboundDispatcher(AgentLoopHarness harness,
                             SessionService sessionService,
                             AgentLockProvider lockProvider,
                             SlashCommandRegistry slashCommands) {
        this.harness = harness;
        this.sessionService = sessionService;
        this.lockProvider = lockProvider;
        this.slashCommands = slashCommands;
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

    /**
     * Channel 把入站消息交给这里 —— 同步走 LLM,完事再回写。
     *
     * <p>**调用方应该在自己的 worker 线程跑**(每个 channel 自己开线程池),
     * 这里会阻塞数秒到数分钟(LLM 慢 + 工具调用)。dispatcher 不持线程池。
     */
    public void dispatch(ChannelMessage msg) {
        String sessionId = sessionIdFor(msg);
        ensureSession(sessionId, msg);

        // s21 Demo 25 副作用 v4:slash 命令优先走客户端路由,不喂 LLM、不进 history。
        // 跟 ChatController + JoojCliRunner 同款。这样 IM 用户发 /clear 真的清 history,
        // 不会被 LLM 当成普通文本礼貌响应"已清空"但实际啥也没清。
        if (slashCommands != null && slashCommands.isCommand(msg.text())) {
            String reply = slashCommands.dispatch(msg.text(), sessionId);
            log.info("[Channel:{}] slash command handled: {} (session={})",
                    msg.channel(), msg.text().strip(), sessionId);
            sendReply(msg, reply);
            return;
        }

        ReentrantLock lock = lockProvider.lockFor(sessionId);
        if (!lock.tryLock()) {
            log.warn("[Channel:{}] session {} busy, dropping inbound from peer={}",
                    msg.channel(), sessionId, msg.peerId());
            return;
        }

        int historySizeBefore;
        try {
            historySizeBefore = sessionService.loadHistory(sessionId).size();
            // s21 Demo 20:把 (channel, peerId) 作为 deliveryHint 透传给 processOneQuery,
            // CronTool 等能拿到 hint freeze 进 self-describing cron job(不依赖任何旁路)。
            ExecutionContext.DeliveryHint hint = new ExecutionContext.DeliveryHint(msg.channel(), msg.peerId());
            harness.processOneQuery(sessionId, msg.text(), hint);
        } catch (Exception e) {
            log.error("[Channel:{}] processOneQuery failed for session {}: {}",
                    msg.channel(), sessionId, e.getMessage(), e);
            return;
        } finally {
            lock.unlock();
        }

        // 取 LLM 这一轮新增的最后一条 assistant text 回写给 peer
        String reply = lastAssistantText(sessionService.loadHistory(sessionId), historySizeBefore);
        if (reply == null || reply.isBlank()) {
            log.info("[Channel:{}] no assistant text to reply (peer={})", msg.channel(), msg.peerId());
            return;
        }
        sendReply(msg, reply);
    }

    /** 通过已注册 channel 把回复发回 peer。失败仅 warn,不抛。 */
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
     * 构造 session id —— 必须严格匹配 SessionStore 的 {@code [a-zA-Z0-9_-]+} 字符集
     * (防路径注入)。冒号 / 点 / 中文等非法字符全替换成 '_'。
     *
     * <p>替换后保持人类可读 + 同 peer 永远同 session(replaceAll 是稳定函数)。
     * 极端 collision(两个不同 peer 替换后撞 id)概率极低,且业务上影响有限(共享上下文)。
     */
    static String sessionIdFor(ChannelMessage msg) {
        String safePeer = msg.peerId().replaceAll("[^a-zA-Z0-9_-]", "_");
        return "chat_" + msg.channel() + "_" + safePeer;
    }

    private void ensureSession(String sessionId, ChannelMessage msg) {
        if (sessionService.exists(sessionId)) return;
        String title = msg.channel() + ":" + (msg.peerName() != null ? msg.peerName() : msg.peerId());
        sessionService.createWithId(sessionId, title);
        log.info("[Channel:{}] auto-created session {} (title={})", msg.channel(), sessionId, title);
    }

    /**
     * {@link ChannelDeliverer} 接口实现 — harness 在 cron 触发完,按 cron job 自描述的
     * (channel, peerId) 调用此方法把 LLM 回复发回去(s21 Demo 20)。
     *
     * <p>设计:dispatcher 降级为单纯执行器 —— 决策"送哪 + 内容是什么"都在 harness,这里只负责
     * 找到 channel bean 调 sendOutbound。**不做任何反查**。
     */
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

    /**
     * 从 sinceIndex 之后的 history 里找最后一条 assistant 文本。
     *
     * <p>跳过 tool_use / thinking,只回纯文本 —— peer 那边不需要看到工具调用细节。
     */
    private String lastAssistantText(List<MessageParam> history, int sinceIndex) {
        for (int i = history.size() - 1; i >= sinceIndex; i--) {
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
        return null;
    }
}
