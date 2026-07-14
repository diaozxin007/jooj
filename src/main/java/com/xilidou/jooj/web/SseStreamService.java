package com.xilidou.jooj.web;

import com.xilidou.jooj.agent.AgentControl;
import com.xilidou.jooj.agent.PendingQuestionRegistered;
import com.xilidou.jooj.agent.TurnEventPushed;
import com.xilidou.jooj.agent.control.ClarifyQuestion;
import com.xilidou.jooj.agent.control.PendingQuestion;
import com.xilidou.jooj.agent.control.PermissionQuestion;
import com.xilidou.jooj.channel.AnswerPresenter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * s22 SSE:管理 per-sid Server-Sent Events 连接,替代前端的 poll。
 *
 * <h3>为什么 SSE 而不是 WebSocket</h3>
 *
 * <p>jooj 的需求都是**单向 push**(server → client):
 * <ul>
 *   <li>Tool 摘要事件(D-11 TurnEventStream 已产出)</li>
 *   <li>Permission / Clarify 挂起(D-10-B / AQ 已产出)</li>
 * </ul>
 *
 * <p>客户端发数据仍走 REST POST(/answer / /interrupt / /chat)。SSE 匹配这个方向 100%,
 * 不需要 WS 双向协议开销。Spring 内置 SseEmitter,无需加依赖。
 *
 * <h3>连接模型</h3>
 *
 * <ul>
 *   <li>**每 sid 一个活跃 emitter**:同 sid 新连接踢掉老的(前端刷新场景)</li>
 *   <li>**心跳 30s**:server 每 30s 发 {@code :keepalive} 注释行,防中间层(nginx/proxy)超时断连</li>
 *   <li>**timeout Long.MAX**:让 emitter 长活;真断连时前端 EventSource 自动重连</li>
 *   <li>**完成/超时/错误 都清理 registry**:避免死引用泄漏</li>
 * </ul>
 *
 * <h3>事件格式</h3>
 *
 * <pre>
 *   event: tool_start
 *   id: 42
 *   data: {"seq":42,"summary":"$ mvn test","type":"tool_start"}
 *
 *   event: pending
 *   id: ask-uuid
 *   data: {"askId":"...","type":"permission",...}
 * </pre>
 *
 * <p>{@code event:} 是 SSE 标准的类型,前端 {@code source.addEventListener("tool_start", ...)}
 * 分派。{@code id:} 让浏览器自动 track {@code Last-Event-ID},重连时 server 可补发。
 */
@Component
@Slf4j
public class SseStreamService implements AnswerPresenter {

    private final ConcurrentHashMap<String, SseEmitter> sessions = new ConcurrentHashMap<>();

    /** s22 SSE:register 时回放已挂起 pending questions。 */
    private final AgentControl agentControl;

    @org.springframework.beans.factory.annotation.Autowired
    public SseStreamService(AgentControl agentControl) {
        this.agentControl = agentControl;
        log.debug("[SSE] SseStreamService wired with agentControl={}",
                agentControl != null ? agentControl.getClass().getSimpleName() : "NULL");
    }

    /** 测试用无参 ctor —— 不做 replay,单测方便。 */
    SseStreamService() {
        this.agentControl = null;
    }

    /**
     * 注册新连接。同 sid 已有 emitter 时,先 complete 老的(前端会重连),再挂新的。
     *
     * @return 新 emitter,caller(ChatController)直接 return 给 Spring
     */
    public SseEmitter register(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId required");
        }
        // 用 Long.MAX_VALUE:让 emitter 长活。真断连由前端 EventSource 自动重连恢复
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        SseEmitter old = sessions.put(sessionId, emitter);
        if (old != null) {
            try { old.complete(); } catch (Exception ignore) {}
            log.info("[SSE] sid={} kicked previous emitter", sessionId);
        }

        emitter.onCompletion(() -> {
            sessions.remove(sessionId, emitter);
            log.debug("[SSE] sid={} completed", sessionId);
        });
        emitter.onTimeout(() -> {
            sessions.remove(sessionId, emitter);
            log.debug("[SSE] sid={} timed out", sessionId);
        });
        emitter.onError(t -> {
            sessions.remove(sessionId, emitter);
            log.debug("[SSE] sid={} error: {}", sessionId, t.toString());
        });

        log.info("[SSE] sid={} registered (total active={})", sessionId, sessions.size());

        // 首个 connected 事件 —— Spring ResponseBodyEmitter 支持 initialize 前 send:
        // 内部 earlySendAttempts 队列会缓存,handler 挂上后 replay。所以直接调即可。
        // 参考 ResponseBodyEmitter#send 源码:if (this.handler == null) earlySendAttempts.add(...)
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"sessionId\":\"" + jsonEscape(sessionId) + "\"}"));
        } catch (Exception e) {
            log.warn("[SSE] sid={} connected send failed: {}", sessionId, e.toString());
        }

        // s22 SSE:回放已挂起的 pending questions —— 防止"SSE 连接前已 ask 挂起,前端
        // 永远收不到 event"的 bug。register 后立即把 agentControl.listPending 都推一遍。
        // 注意直接调 present(不走 event 分派),因为这是**已挂起**的 pending 补发,
        // event 早就发过被别的 presenter 认领(或没人认领),这里补一份给新连上的浏览器。
        if (agentControl != null) {
            for (PendingQuestion q : agentControl.listPending(sessionId)) {
                if (supports(sessionId, q)) {
                    present(sessionId, q);
                }
            }
        }

        return emitter;
    }

    /** 简单 JSON 字符串转义(不引 Jackson 保持轻量)。 */
    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 推一个自定义事件到指定 sid 的 emitter(sid 无连接时 no-op)。
     *
     * @param eventName SSE {@code event:} 字段,如 {@code "tool_start"} / {@code "pending"}
     * @param id        SSE {@code id:} 字段,给浏览器 Last-Event-ID 用;可 null
     * @param jsonPayload data 字段的 JSON body(caller 自己拼)
     */
    public void push(String sessionId, String eventName, String id, String jsonPayload) {
        if (sessionId == null || sessionId.isBlank()) return;
        SseEmitter emitter = sessions.get(sessionId);
        if (emitter == null) {
            log.warn("[SSE] push NO EMITTER sid={} event={} id={} activeSessions={}",
                    sessionId, eventName, id, sessions.keySet());
            return;
        }

        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(eventName);
            if (id != null) builder = builder.id(id);
            builder = builder.data(jsonPayload);
            emitter.send(builder);
            log.debug("[SSE] pushed sid={} event={} id={} bytes={}",
                    sessionId, eventName, id, jsonPayload.length());
        } catch (IOException e) {
            // 客户端断了或 IO 挂了 —— 清理 emitter,前端 EventSource 会自动重连再注册
            log.debug("[SSE] sid={} push failed, dropping emitter: {}", sessionId, e.toString());
            sessions.remove(sessionId, emitter);
            try { emitter.complete(); } catch (Exception ignore) {}
        } catch (IllegalStateException ise) {
            // emitter 已 complete —— 同上
            sessions.remove(sessionId, emitter);
        }
    }

    /**
     * 主动断开 sid 的连接(session 删除 / 清空时用)。
     */
    public void close(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        SseEmitter emitter = sessions.remove(sessionId);
        if (emitter != null) {
            try { emitter.complete(); } catch (Exception ignore) {}
        }
    }

    /** 测试可见:当前活跃连接数。 */
    int activeCount() {
        return sessions.size();
    }

    /** 测试可见:sid 是否有活跃连接。 */
    boolean hasConnection(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return sessions.containsKey(sessionId);
    }

    /**
     * 心跳:每 30s 给所有活跃 emitter 发一行注释({@code :}),防中间层超时断连。
     * SSE 规范里 {@code :} 开头的行被客户端忽略,只作 keep-alive。
     */
    @Scheduled(fixedDelay = 30_000)
    void heartbeat() {
        if (sessions.isEmpty()) return;
        for (Map.Entry<String, SseEmitter> e : sessions.entrySet()) {
            SseEmitter emitter = e.getValue();
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception ex) {
                sessions.remove(e.getKey(), emitter);
                log.debug("[SSE] heartbeat drop sid={}: {}", e.getKey(), ex.toString());
            }
        }
    }

    // ── Spring event listener 接入 agent 层的 push 点 ──────────────

    /**
     * s22 SSE:TurnEventStream.push 时,agent 层发 {@link TurnEventPushed} 事件,
     * 此处监听后立即通过 SSE 推给浏览器,替代前端 poll /events。
     */
    @EventListener
    void onTurnEvent(TurnEventPushed evt) {
        log.debug("[SSE] onTurnEvent received sid={} seq={} type={}",
                evt.sessionId(),
                evt.event() != null ? evt.event().seq() : -1,
                evt.event() != null ? evt.event().type() : "null");
        if (evt.event() == null) return;
        String json = "{\"seq\":" + evt.event().seq()
                + ",\"at\":\"" + evt.event().at() + "\""
                + ",\"type\":\"" + evt.event().type() + "\""
                + ",\"summary\":" + jsonString(evt.event().summary()) + "}";
        push(evt.sessionId(), "tool_start", String.valueOf(evt.event().seq()), json);
    }

    /**
     * s22 D-12:{@link AnswerPresenter#supports} —— web 场景 sid 通常无前缀 或
     * {@code chat_web_}。sid 以 {@code chat_weixin_} 开头意味着微信,归 WeixinPresenter,
     * 我这里返 false。
     *
     * <p>如果 question 上带 originChannel,那就 100% 判 == "web"。
     * 无 originChannel 时按 sid 前缀 heuristic。
     */
    @Override
    public boolean supports(String sessionId, PendingQuestion question) {
        String ch = question.originChannel();
        if (ch != null) return "web".equals(ch);
        // heuristic:非 chat_<channel>_ 前缀的都当 web(default / sid-xxx / chat_web_xxx 等)
        return sessionId == null || !sessionId.startsWith("chat_") || sessionId.startsWith("chat_web_");
    }

    /**
     * s22 SSE:{@link AnswerPresenter#present} 实现 —— 把 question 序列化为 JSON,
     * 通过已经建好的 SSE emitter 推到浏览器。以前挂 @EventListener 直接监听,
     * 现在改由 {@code PresenterRegistry} 分派(避免 SSE 抢占本该走微信的 event)。
     */
    @Override
    public void present(String sessionId, PendingQuestion q) {
        if (q == null) return;
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"askId\":").append(jsonString(q.askId())).append(",");
        sb.append("\"type\":").append(jsonString(q.type())).append(",");
        sb.append("\"askedAt\":").append(jsonString(q.askedAt().toString()));
        if (q instanceof PermissionQuestion pq) {
            sb.append(",\"toolName\":").append(jsonString(pq.toolName()));
            sb.append(",\"toolInput\":").append(jsonString(pq.toolInput()));
            sb.append(",\"reason\":").append(jsonString(pq.reason()));
        } else if (q instanceof ClarifyQuestion cq) {
            sb.append(",\"questions\":[");
            for (int i = 0; i < cq.questions().size(); i++) {
                if (i > 0) sb.append(",");
                ClarifyQuestion.SubQuestion sq = cq.questions().get(i);
                sb.append("{\"question\":").append(jsonString(sq.question()))
                        .append(",\"header\":").append(jsonString(sq.header()))
                        .append(",\"multiSelect\":").append(sq.multiSelect())
                        .append(",\"options\":[");
                for (int j = 0; j < sq.options().size(); j++) {
                    if (j > 0) sb.append(",");
                    ClarifyQuestion.Option op = sq.options().get(j);
                    sb.append("{\"label\":").append(jsonString(op.label()));
                    if (op.description() != null) {
                        sb.append(",\"description\":").append(jsonString(op.description()));
                    }
                    sb.append("}");
                }
                sb.append("]}");
            }
            sb.append("]");
        }
        sb.append("}");
        push(sessionId, "pending", q.askId(), sb.toString());
    }

    /** 简单 JSON 字符串转义(不引 Jackson 保持轻量)。 */
    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
