package com.xilidou.jooj.web;

import com.xilidou.jooj.channel.InboundDispatcher;
import com.xilidou.jooj.channel.InboundDispatcher.DispatchRequest;
import com.xilidou.jooj.channel.InboundDispatcher.DispatchResult;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * jooj REST 接口 —— 所有请求统一收拢到 {@link InboundDispatcher}。
 *
 * <h3>三个 endpoint</h3>
 *
 * <ul>
 *   <li>{@code POST /api/chat}     喂一条 query,返 assistant reply + 历史长度 + 本轮工具调用列表</li>
 *   <li>{@code GET  /api/history}  完整对话历史(所有 user/assistant 揉平为 role+text)</li>
 *   <li>{@code POST /api/clear}    清空对话历史</li>
 * </ul>
 *
 * <h3>职责划分</h3>
 *
 * <p>Controller 只负责两件事:
 * <ol>
 *   <li>把 HTTP 请求参数适配成 {@link DispatchRequest}</li>
 *   <li>把 {@link DispatchResult} 映射成 HTTP status(200 / 400 / 409 / 500)</li>
 * </ol>
 *
 * <p>pipeline 本身(session 解析/校验、slash 短路、UserPromptHook、per-session lock、
 * LLM 调用、reply 抽取、toolCalls 收集)全在 {@link InboundDispatcher} 里,跟 Channel
 * (微信/Discord)入站共用同一份实现,避免行为漂移。
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

    private final InboundDispatcher dispatcher;

    public ChatController(InboundDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 主对话 endpoint —— 全部逻辑走 {@link InboundDispatcher#dispatchSync}。
     *
     * <p>Web 侧 {@code autoCreate=false}:未知 sessionId 返 400 "session not found",
     * 保留原语义(避免前端 bug 静默漂到 DEFAULT session)。
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(error("request body required"));
        }
        DispatchResult r = dispatcher.dispatchSync(new DispatchRequest(
                request.getSessionId(),
                request.getQuery(),
                /* autoCreate    */ false,
                /* autoCreateTitle */ null,
                /* hint          */ null));

        return switch (r.status()) {
            case OK, SLASH_HANDLED ->
                    ResponseEntity.ok(new ChatResponse(
                            r.reply(), r.historySize(), r.toolCallsThisTurn()));
            case HOOK_BLOCKED ->
                    // reply 已带 "⛔ Prompt blocked: " 前缀,直接作为 error 消息返 400
                    ResponseEntity.badRequest().body(error(r.reply()));
            case BAD_REQUEST, SESSION_NOT_FOUND ->
                    ResponseEntity.badRequest().body(error(r.errorMessage()));
            case SESSION_BUSY ->
                    ResponseEntity.status(409).body(error(r.errorMessage()));
            case AGENT_FAILED ->
                    ResponseEntity.status(500).body(error(r.errorMessage()));
        };
    }

    /**
     * 完整对话历史(只给前端展示用)。
     *
     * <p>跳过揉平后为空的消息 —— 协议内部状态(tool_use only / tool_result only 那些
     * message),前端用户不该看到这些占位气泡。
     */
    @GetMapping("/history")
    public HistoryResponse history(@RequestParam(required = false) String sessionId) {
        List<MessageParam> hist = dispatcher.history(sessionId);
        List<HistoryResponse.Entry> entries = new ArrayList<>(hist.size());
        for (MessageParam m : hist) {
            String text = flattenContent(m);
            if (text == null || text.isBlank()) continue;
            entries.add(new HistoryResponse.Entry(m.getRole(), text));
        }
        return new HistoryResponse(entries);
    }

    /** 清空对话历史。走 {@link InboundDispatcher#clearHistory} 共享 per-session lock。 */
    @PostMapping("/clear")
    public ResponseEntity<?> clear(@RequestParam(required = false) String sessionId) {
        if (!dispatcher.clearHistory(sessionId)) {
            return ResponseEntity.status(409).body(error("Session busy. Please retry."));
        }
        return ResponseEntity.ok(new ChatResponse(null, 0, List.of()));
    }

    // ─────────────────────────────────────────────────────────────
    //  view helper:MessageParam → 纯文本(前端展示)
    // ─────────────────────────────────────────────────────────────

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

    private static Map<String, String> error(String msg) {
        return Map.of("error", msg);
    }
}