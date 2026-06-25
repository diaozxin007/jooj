package com.xilidou.marvis.web;

import com.xilidou.marvis.agent.AgentLoopHarness;
import com.xilidou.marvis.http.dto.MessageParam;
import com.xilidou.marvis.http.dto.TextBlock;
import com.xilidou.marvis.http.dto.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * marvis 的 REST 接口 —— 让浏览器(或 curl/Postman)代替 CLI REPL 跟 agent 交互。
 *
 * <h3>三个 endpoint</h3>
 *
 * <ul>
 *   <li>{@code POST /api/chat}     喂一条 query,返 assistant reply + 历史长度 + 本轮工具调用列表</li>
 *   <li>{@code GET  /api/history}  完整对话历史(所有 user/assistant 揉平为 role+text)</li>
 *   <li>{@code POST /api/clear}    清空对话历史</li>
 * </ul>
 *
 * <h3>关键设计</h3>
 *
 * <ul>
 *   <li><b>共享 {@code AgentLoopHarness} 单例</b>:跟 CLI 模式用同一个 harness bean,
 *       同一个 history 字段。意味着同时跑 CLI 和 Web 会**串聊**(messages 共享)——
 *       v1 单用户场景接受,生产化要做 session 隔离</li>
 *   <li><b>{@code agentLock} 互斥</b>:跟 cron processor / CLI REPL 共享同一把锁。
 *       多个 web 请求会串行化执行,不并发(单 marvis 单 agent 设计前提)</li>
 *   <li><b>{@code last assistant text} 的提取</b>:从 {@code history.last} 拿
 *       TextBlock 拼成字符串。跟 CLI 路径的 {@code printLastAssistantText} 等价,
 *       只是改成返回字符串而非打印</li>
 * </ul>
 *
 * <h3>不做的事</h3>
 *
 * <ul>
 *   <li>不做 SSE 流式响应 —— 工具中间过程仍打到 stdout(后端日志可见,前端等总响应)</li>
 *   <li>不做 session 管理 —— 单 history 单用户</li>
 *   <li>不做 CSRF / auth —— v1 仅本机访问,生产化再补</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class ChatController {

    private final AgentLoopHarness harness;
    private final ReentrantLock agentLock;

    public ChatController(AgentLoopHarness harness,
                          @Qualifier("agentLock") ReentrantLock agentLock) {
        this.harness = harness;
        this.agentLock = agentLock;
    }

    /**
     * 主对话 endpoint。
     *
     * <p>逻辑:
     * <ol>
     *   <li>校验 query 非空 → 400</li>
     *   <li>{@code agentLock.tryLock()} 抢锁,失败 → 409 (cron / 别的请求正在跑)</li>
     *   <li>记录调用前 history.size,调 {@code processOneQuery},找 turn 内调用的工具</li>
     *   <li>从 history.last 拼 last assistant text 返回</li>
     * </ol>
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.badRequest().body(error("query must not be blank"));
        }

        if (!agentLock.tryLock()) {
            return ResponseEntity.status(409).body(error(
                    "Agent busy (a scheduled task or another request is running). Please retry."));
        }
        try {
            int historyBefore = harness.getHistory().size();
            try {
                harness.processOneQuery(request.getQuery());
            } catch (Exception e) {
                log.error("[Web] processOneQuery failed", e);
                return ResponseEntity.status(500).body(error(
                        "Agent failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }

            List<MessageParam> history = harness.getHistory();
            String reply = extractLastAssistantText(history);
            List<String> toolCalls = collectToolCallsSince(history, historyBefore);

            return ResponseEntity.ok(new ChatResponse(reply, history.size(), toolCalls));
        } finally {
            agentLock.unlock();
        }
    }

    /**
     * 完整对话历史(只给前端展示用)。
     *
     * <p>跳过揉平后是空的消息 —— 协议内部状态(tool_use only / tool_result only
     * 那些 message),前端用户不该看到这些占位气泡。具体哪些会被跳过:
     * <ul>
     *   <li>assistant 一轮纯 ToolUseBlock + ThinkingBlock(没 TextBlock)</li>
     *   <li>user 一轮纯 ToolResultBlock(协议要求 tool_use 后紧跟的 user 消息)</li>
     * </ul>
     */
    @GetMapping("/history")
    public HistoryResponse history() {
        List<MessageParam> hist = harness.getHistory();
        List<HistoryResponse.Entry> entries = new ArrayList<>(hist.size());
        for (MessageParam m : hist) {
            String text = flattenContent(m);
            if (text == null || text.isBlank()) continue;
            entries.add(new HistoryResponse.Entry(m.getRole(), text));
        }
        return new HistoryResponse(entries);
    }

    /** 清空对话历史。复用 {@link AgentLoopHarness#clearHistory}。 */
    @PostMapping("/clear")
    public ResponseEntity<?> clear() {
        if (!agentLock.tryLock()) {
            return ResponseEntity.status(409).body(error("Agent busy. Please retry."));
        }
        try {
            harness.clearHistory();
            return ResponseEntity.ok(new ChatResponse(null, 0, List.of()));
        } finally {
            agentLock.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  内部辅助
    // ─────────────────────────────────────────────────────────────

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
                // - ToolUseBlock / ToolResultBlock — 协议内部状态,工具名通过单独的
                //   toolCalls 字段返(ChatResponse),气泡里不再重复展示 "[tool: bash]"
                // - ThinkingBlock — Claude Sonnet 4.x extended thinking 块,内部推理
                //   过程,前端用户看到"[thinking]"占位会很迷惑
                // - UnknownBlock — 未知协议块,跳过更安全
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * 收集本次 turn 在 history 增量里出现的工具名(去重保序)。
     *
     * <p>只看 assistant 消息里的 ToolUseBlock。多轮工具调用都在 history[before..end] 区间。
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
