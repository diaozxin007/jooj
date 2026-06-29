package com.xilidou.jooj.web;

import com.xilidou.jooj.agent.AgentLoopHarness;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import com.xilidou.jooj.session.AgentLockProvider;
import com.xilidou.jooj.session.Session;
import com.xilidou.jooj.session.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * jooj 的 REST 接口 —— 让浏览器(或 curl/Postman)代替 CLI REPL 跟 agent 交互。
 *
 * <h3>三个 endpoint</h3>
 *
 * <ul>
 *   <li>{@code POST /api/chat}     喂一条 query,返 assistant reply + 历史长度 + 本轮工具调用列表</li>
 *   <li>{@code GET  /api/history}  完整对话历史(所有 user/assistant 揉平为 role+text)</li>
 *   <li>{@code POST /api/clear}    清空对话历史</li>
 * </ul>
 *
 * <h3>关键设计 — Session 抽象</h3>
 *
 * <ul>
 *   <li><b>每个请求都带 sessionId</b>:body 里(POST /chat)或 query param 里(GET /history、
 *       POST /clear)。空白 sessionId 退化到 {@link Session#DEFAULT_ID} 兜底,
 *       让旧前端 / curl 不带也能跑</li>
 *   <li><b>Per-session 锁</b>:通过 {@link AgentLockProvider#lockFor(String)} 拿,
 *       不同 session 可以并行,同 session 互斥防 messages list 撞车</li>
 *   <li><b>历史 per-session</b>:{@link AgentLoopHarness#getHistory(String)}
 *       返回的是该 session 自己的 list,不会跟别的 session 串味</li>
 * </ul>
 *
 * <h3>不做的事</h3>
 *
 * <ul>
 *   <li>不做 SSE 流式响应 —— 工具中间过程仍打到 stdout</li>
 *   <li>不做 CSRF / auth —— v1 仅本机访问,生产化再补</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class ChatController {

    private final AgentLoopHarness harness;
    private final SessionService sessionService;
    private final AgentLockProvider lockProvider;

    public ChatController(AgentLoopHarness harness,
                          SessionService sessionService,
                          AgentLockProvider lockProvider) {
        this.harness = harness;
        this.sessionService = sessionService;
        this.lockProvider = lockProvider;
    }

    /**
     * 主对话 endpoint。
     *
     * <p>逻辑:
     * <ol>
     *   <li>校验 query / sessionId 非空 → 400</li>
     *   <li>{@link AgentLockProvider#lockFor(String)}.tryLock() 抢 per-session 锁,
     *       失败 → 409 (此 session 正在跑别的请求)</li>
     *   <li>记录调用前 history.size,调 {@code processOneQuery},找 turn 内调用的工具</li>
     *   <li>从 history.last 拼 last assistant text 返回</li>
     * </ol>
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.badRequest().body(error("query must not be blank"));
        }
        String sessionId = resolveSessionId(request.getSessionId());
        if (!sessionService.exists(sessionId)) {
            return ResponseEntity.badRequest().body(error("session not found: " + sessionId));
        }

        ReentrantLock lock = lockProvider.lockFor(sessionId);
        if (!lock.tryLock()) {
            return ResponseEntity.status(409).body(error(
                    "Session busy (another request is running for this session). Please retry."));
        }
        try {
            int historyBefore = harness.getHistory(sessionId).size();
            try {
                harness.processOneQuery(sessionId, request.getQuery());
            } catch (Exception e) {
                log.error("[Web] processOneQuery failed", e);
                return ResponseEntity.status(500).body(error(
                        "Agent failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }

            List<MessageParam> history = harness.getHistory(sessionId);
            String reply = extractLastAssistantText(history);
            List<String> toolCalls = collectToolCallsSince(history, historyBefore);

            return ResponseEntity.ok(new ChatResponse(reply, history.size(), toolCalls));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 完整对话历史(只给前端展示用)。
     *
     * <p>跳过揉平后是空的消息 —— 协议内部状态(tool_use only / tool_result only
     * 那些 message),前端用户不该看到这些占位气泡。
     */
    @GetMapping("/history")
    public HistoryResponse history(@RequestParam(required = false) String sessionId) {
        String sid = resolveSessionId(sessionId);
        List<MessageParam> hist = harness.getHistory(sid);
        List<HistoryResponse.Entry> entries = new ArrayList<>(hist.size());
        for (MessageParam m : hist) {
            String text = flattenContent(m);
            if (text == null || text.isBlank()) continue;
            entries.add(new HistoryResponse.Entry(m.getRole(), text));
        }
        return new HistoryResponse(entries);
    }

    /** 清空对话历史。复用 {@link AgentLoopHarness#clearHistory(String)}。 */
    @PostMapping("/clear")
    public ResponseEntity<?> clear(@RequestParam(required = false) String sessionId) {
        String sid = resolveSessionId(sessionId);
        ReentrantLock lock = lockProvider.lockFor(sid);
        if (!lock.tryLock()) {
            return ResponseEntity.status(409).body(error("Session busy. Please retry."));
        }
        try {
            harness.clearHistory(sid);
            return ResponseEntity.ok(new ChatResponse(null, 0, List.of()));
        } finally {
            lock.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  内部辅助
    // ─────────────────────────────────────────────────────────────

    /** 空白 sessionId → {@link Session#DEFAULT_ID} 兜底。 */
    private static String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Session.DEFAULT_ID;
        return sessionId.trim();
    }

    /** 从 history.last 抽 TextBlock 拼成字符串。empty / 非 assistant → 空串。 */
    private static String extractLastAssistantText(List<MessageParam> history) {
        if (history.isEmpty()) return "";
        MessageParam last = history.get(history.size() - 1);
        if (!"assistant".equals(last.getRole())) return "";
        return flattenContent(last);
    }

    /** 把 MessageParam 揉平成纯文本(只取用户可见的 TextBlock,协议内部 block 全跳过)。 */
    private static String flattenContent(MessageParam m) {
        Object content = m.getContent();
        if (content instanceof String s) return s;
        if (content instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object b : blocks) {
                if (b instanceof TextBlock t) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t.getText());
                }
                // ⚠️ 故意跳过其他 block 类型:
                // - ToolUseBlock / ToolResultBlock — 协议内部状态
                // - ThinkingBlock — Claude Sonnet 4.x extended thinking
                // - UnknownBlock — 未知协议块,跳过更安全
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * 收集本次 turn 在 history 增量里出现的工具名(去重保序)。
     */
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

    private static java.util.Map<String, String> error(String msg) {
        return java.util.Map.of("error", msg);
    }
}
