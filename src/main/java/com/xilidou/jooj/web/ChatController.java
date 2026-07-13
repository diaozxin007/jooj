package com.xilidou.jooj.web;

import com.xilidou.jooj.channel.InboundDispatcher;
import com.xilidou.jooj.channel.InboundDispatcher.DispatchRequest;
import com.xilidou.jooj.channel.InboundDispatcher.DispatchResult;
import com.xilidou.jooj.compact.CompactConfig;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.transcript.TranscriptLine;
import com.xilidou.jooj.transcript.TranscriptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JacksonConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * jooj REST 接口 —— 所有请求统一收拢到 {@link InboundDispatcher}。
 *
 * <h3>Endpoints</h3>
 *
 * <ul>
 *   <li>{@code POST /api/chat}          喂一条 query,返 assistant reply + 本轮新增展示项</li>
 *   <li>{@code GET  /api/chat-history}  完整对话展示(前端主渲染)—— s22 P4 从 transcript 派生</li>
 *   <li>{@code GET  /api/history}       简化对话展示(role + text 列表)—— s22 P4 从 transcript 派生</li>
 *   <li>{@code POST /api/clear}         清空对话历史</li>
 *   <li>{@code GET  /api/snip-archive}  展开 SnipCompactor 归档 jsonl(session domain 独立特性)</li>
 * </ul>
 *
 * <h3>s22 P4:主渲染切换到 transcript</h3>
 *
 * <p>{@link #chatHistory} / {@link #history} 从 {@code sessionService.loadHistory} 切到
 * {@code transcriptService.readAll} —— 前端**只**看用户 ↔ lead-agent 干净对话,不再直接感知
 * tool 中间态、thinking、cron/inbox 注入、压缩归档等 loop 内部行为。参考 s22 §4.5。
 *
 * <p>失去的展示能力(接受的产品权衡):TOOL_CALL 卡 / THINKING 折叠 / ARCHIVE 通知 / bg
 * placeholder 合并全部消失。cron 消息透过 role="scheduled" 保留为 SYSTEM_NOTICE(CRON)。
 *
 * <p>{@link #snipArchive} 未切 —— 它服务 session 域的压缩归档展开,不属于 transcript 范畴,
 * 未来若 archive 移到 transcript 再统一。
 *
 * <h3>职责划分</h3>
 *
 * <p>Controller 只负责两件事:
 * <ol>
 *   <li>把 HTTP 请求参数适配成 {@link DispatchRequest} / transcript 查询</li>
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
    private final CompactConfig compactConfig;
    private final TranscriptService transcriptService;
    private final ObjectMapper json = JacksonConfig.newMapper();

    public ChatController(InboundDispatcher dispatcher,
                          CompactConfig compactConfig,
                          TranscriptService transcriptService) {
        this.dispatcher = dispatcher;
        this.compactConfig = compactConfig;
        this.transcriptService = transcriptService;
    }

    /**
     * 主对话 endpoint —— 全部逻辑走 {@link InboundDispatcher#dispatchSync}。
     *
     * <p>Web 侧 {@code autoCreate=false}:未知 sessionId 返 400 "session not found",
     * 保留原语义(避免前端 bug 静默漂到 DEFAULT session)。
     *
     * <p>返回体 {@link ChatResponse} 除了 {@code reply}(向后兼容 channel 场景)之外,
     * 还带一个 {@code newItems} —— 本回合展示层新增的 {@link ChatItem} 列表,前端直接 append,
     * 不再靠 "reply || '(no reply)'" 猜测。
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(error("request body required"));
        }
        // 注意:不能提前调 dispatcher.history() 拿 historyBefore —— history() 会
        // 触发 SessionService.loadHistory 的"未知 session 自动创建"分支,导致
        // 后面 dispatchSync 的 SESSION_NOT_FOUND 拒绝路径永远走不到,unknown session
        // 请求变成 500(processOneQuery 抛)而不是我们想要的 400。
        // 改用 sessionService.getHistorySize(sessionId) 之类的"只读、不 create"接口
        // 是更干净的解,但当下先用 dispatchSync 完成后的 historySize 反推 —— 不引入新方法。
        DispatchResult r = dispatcher.dispatchSync(new DispatchRequest(
                request.getSessionId(),
                request.getQuery(),
                /* autoCreate    */ false,
                /* autoCreateTitle */ null,
                /* hint          */ null));

        return switch (r.status()) {
            case OK, SLASH_HANDLED -> {
                // s22 P4:newItems 从 transcript 派生,不再走 session history + ChatHistoryMapper
                List<TranscriptLine> full = safeTranscriptReadAll(request.getSessionId());
                List<ChatItem> newItems = tailAfterLastUserInput(
                        TranscriptToChatItemMapper.map(full));
                yield ResponseEntity.ok(new ChatResponse(
                        r.reply(), r.historySize(), r.toolCallsThisTurn(), newItems));
            }
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
     * 取"最后一条 USER_INPUT 展示项及其之后的所有 items" —— 即本回合的展示片段。
     *
     * <p>为什么用这个而不是 historyBefore..historyAfter 的原始 index 切片:见上方 chat() 里的注释。
     * 一个 turn 的定义就是"用户说话到 LLM 停止说话之间",而 USER_INPUT 展示项正好一一对应
     * 用户的 REST 输入。cron/memory/inbox 之类的注入不是 USER_INPUT,会顺着落在末尾一起返回。
     */
    private static List<ChatItem> tailAfterLastUserInput(List<ChatItem> all) {
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).type() == ChatItem.Type.USER_INPUT) {
                return new ArrayList<>(all.subList(i, all.size()));
            }
        }
        return all;
    }

    /**
     * 展示层完整对话历史(前端主渲染路径)。
     *
     * <p>s22 P4:从 {@code transcriptService.readAll(sid)} 派生,通过
     * {@link TranscriptToChatItemMapper} 映射成 {@link ChatItem} 列表。
     * transcript 是干净的用户 ↔ lead-agent 对话记录,不含 tool 中间态、thinking、
     * loop 内部注入、压缩归档。
     *
     * <p>切换 session、页面首次加载都走这个。取代了 {@link #history(String)} 作为 UI 数据源。
     */
    @GetMapping("/chat-history")
    public ChatHistoryResponse chatHistory(@RequestParam(required = false) String sessionId) {
        List<TranscriptLine> lines = safeTranscriptReadAll(sessionId);
        return new ChatHistoryResponse(TranscriptToChatItemMapper.map(lines));
    }

    /**
     * 完整对话历史(只给前端展示用)。
     *
     * <p>s22 P4:同样从 transcript 派生。跳过空 content 行(scheduled/user/assistant 里
     * blank 的),前端用户不该看到空气泡。
     *
     * <p>相比 {@link #chatHistory} 只返 {@code (role, text)} 简化 pair —— 后者返 ChatItem
     * 完整 shape(带 SYSTEM_NOTICE 分类等)。两者各服务不同前端组件。
     */
    @GetMapping("/history")
    public HistoryResponse history(@RequestParam(required = false) String sessionId) {
        List<TranscriptLine> lines = safeTranscriptReadAll(sessionId);
        List<HistoryResponse.Entry> entries = new ArrayList<>(lines.size());
        for (TranscriptLine line : lines) {
            String text = line.content();
            if (text == null || text.isBlank()) continue;
            entries.add(new HistoryResponse.Entry(line.role(), text));
        }
        return new HistoryResponse(entries);
    }

    /**
     * 读取 SnipCompactor 归档的 jsonl 文件,返回揉平后的历史条目。
     *
     * <p>前端从 {@code [snipped N messages, archived to /abs/path.jsonl]} 占位气泡里解析出 path,
     * 传给这个 endpoint 展开原文。
     *
     * <p><b>路径穿越防御</b>:请求携带的 path 必须规范化后落在 {@link CompactConfig#transcriptDir()}
     * 目录内,否则 400。防止恶意前端构造 {@code /etc/passwd} 之类的绝对路径。
     *
     * @param path 归档文件绝对路径(前端从占位气泡文本里解析出来)
     * @return 与 {@code /api/history} 同结构,{@link HistoryResponse.Entry} 列表
     */
    @GetMapping("/snip-archive")
    public ResponseEntity<?> snipArchive(@RequestParam String path) {
        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest().body(error("path required"));
        }
        Path target;
        Path baseDir;
        try {
            target = Path.of(path).toAbsolutePath().normalize();
            baseDir = compactConfig.transcriptDir().toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(error("invalid path: " + e.getMessage()));
        }
        if (!target.startsWith(baseDir)) {
            log.warn("[SnipArchive] rejected out-of-dir path: {} (base={})", target, baseDir);
            return ResponseEntity.badRequest().body(error("path outside archive dir"));
        }
        if (!Files.isRegularFile(target)) {
            return ResponseEntity.status(404).body(error("archive not found"));
        }
        List<HistoryResponse.Entry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(target)) {
                if (line.isBlank()) continue;
                MessageParam m = json.readValue(line, MessageParam.class);
                String text = flattenContent(m);
                if (text == null || text.isBlank()) continue;
                entries.add(new HistoryResponse.Entry(m.getRole(), text));
            }
        } catch (IOException e) {
            log.warn("[SnipArchive] read failed: {} ({})", target, e.toString());
            return ResponseEntity.status(500).body(error("read failed: " + e.getMessage()));
        }
        return ResponseEntity.ok(new HistoryResponse(entries));
    }

    /** 清空对话历史。走 {@link InboundDispatcher#clearHistory} 共享 per-session lock。 */
    @PostMapping("/clear")
    public ResponseEntity<?> clear(@RequestParam(required = false) String sessionId) {
        if (!dispatcher.clearHistory(sessionId)) {
            return ResponseEntity.status(409).body(error("Session busy. Please retry."));
        }
        return ResponseEntity.ok(new ChatResponse(null, 0, List.of(), List.of()));
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

    /**
     * s22 P4:安全读 transcript —— sessionId 校验 + IO 异常兜底(warn log 返空 list)。
     *
     * <p>为什么不直接调 {@code transcriptService.readAll}:
     * <ul>
     *   <li>sessionId 为 null / blank 时,transcriptService 内部会抛 IllegalArgumentException;
     *       前端(切 session 中间态、页面刚打开)可能传空,这里默默返空更友好</li>
     *   <li>磁盘 IO 失败时不该让整个 /chat-history 返 500,返空 + warn 是更好的降级</li>
     * </ul>
     */
    private List<TranscriptLine> safeTranscriptReadAll(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        try {
            return transcriptService.readAll(sessionId);
        } catch (IllegalArgumentException e) {
            log.warn("[ChatController] invalid sessionId for transcript: {}", sessionId);
            return List.of();
        } catch (IOException e) {
            log.warn("[ChatController] transcript IO failed sid={}: {}",
                    sessionId, e.toString());
            return List.of();
        }
    }

    private static Map<String, String> error(String msg) {
        return Map.of("error", msg);
    }
}