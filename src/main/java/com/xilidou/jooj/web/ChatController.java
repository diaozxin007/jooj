package com.xilidou.jooj.web;

import com.xilidou.jooj.agent.AgentControl;
import com.xilidou.jooj.agent.TurnEvent;
import com.xilidou.jooj.agent.TurnEventStream;
import com.xilidou.jooj.agent.control.AllowAnswer;
import com.xilidou.jooj.agent.control.Answer;
import com.xilidou.jooj.agent.control.ChoiceAnswer;
import com.xilidou.jooj.agent.control.ClarifyQuestion;
import com.xilidou.jooj.agent.control.DenyAnswer;
import com.xilidou.jooj.agent.control.PendingQuestion;
import com.xilidou.jooj.agent.control.PermissionQuestion;
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
    private final AgentControl agentControl;
    /** s22 D-11:agent turn 期间 tool 摘要事件流,前端 poll 拿实时进度。 */
    private final TurnEventStream turnEventStream;
    private final ObjectMapper json = JacksonConfig.newMapper();

    public ChatController(InboundDispatcher dispatcher,
                          CompactConfig compactConfig,
                          TranscriptService transcriptService,
                          AgentControl agentControl,
                          TurnEventStream turnEventStream) {
        this.dispatcher = dispatcher;
        this.compactConfig = compactConfig;
        this.transcriptService = transcriptService;
        this.agentControl = agentControl;
        this.turnEventStream = turnEventStream;
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

    /**
     * s22 D-8:用户主动打断当前 turn。
     *
     * <p>把 sessionId 登记到 {@link AgentControl} 挂起集合;agentLoop 在下一个检查点
     * (while 顶部 / tool 循环之间)消费 flag 并抛 {@link com.xilidou.jooj.agent.AgentInterruptedException},
     * 由 processOneQuery 兜底 append {@code [Interrupted by user]} + publish
     * {@link com.xilidou.jooj.transcript.TurnInterrupted} 事件。
     *
     * <p><b>不 block 等 turn 真结束</b>:endpoint 立即返 200(登记成功) / 400(sid 空)。
     * 前端应该:
     * <ol>
     *   <li>发起 POST /interrupt(不 await 结果)</li>
     *   <li>继续等待原来那个 POST /chat 请求返回 —— 拿到的 response 会是"截断到打断点"的状态</li>
     * </ol>
     *
     * <p>幂等:重复调返 200 但 body 里 {@code requested=false},表示已在挂起状态。
     * 前端可用于判断"我上次点了没?"。
     *
     * @param sessionId 目标 session
     * @return 200 with { requested: true|false, sessionId }
     */
    @PostMapping("/chat/{sessionId}/interrupt")
    public ResponseEntity<?> interrupt(@PathVariable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(error("sessionId required"));
        }
        boolean firstRequest = agentControl.requestInterrupt(sessionId);
        log.info("[Interrupt] REST request sid={} firstRequest={}", sessionId, firstRequest);
        return ResponseEntity.ok(Map.of(
                "requested", firstRequest,
                "sessionId", sessionId));
    }

    /**
     * s22 D-10-B:查询当前 session 挂起的 pending question(0 或多个)。
     *
     * <p>前端每秒 poll 一次;pending 非空时弹对话框。
     *
     * <p><b>Response 格式</b>:
     * <pre>
     *   {
     *     "sessionId": "sid-xxx",
     *     "pending": [
     *       {
     *         "askId": "uuid",
     *         "type": "permission",
     *         "askedAt": "2026-07-13T12:34:56.789Z",
     *         "toolName": "bash",         // 各 question 类型自己的字段
     *         "toolInput": "{cmd: rm -rf}",
     *         "reason": "matched destructive command pattern"
     *       }
     *     ]
     *   }
     * </pre>
     */
    /**
     * s22 D-11:查询 agent turn 期间产生的**摘要事件流**,给前端 poll 更新 loading 气泡。
     *
     * <p>前端 turn 期间每 800ms poll,拿 seq > {@code since} 的增量事件,更新 UI:
     * <pre>
     *   ▶ 正在执行: $ mvn test
     *   ▶ 正在执行: [sub] 📖 pom.xml
     *   ▶ 正在执行: [teammate:alice] 🔎 "user auth"
     * </pre>
     *
     * <p>用完即弃语义:{@code turnEventStream.clear(sid)} 在 processOneQuery 结束调
     * (D-11-c),下一 turn 从 seq=1 重新开始。前端 turn 开始时 since=0 拉全量,
     * 之后传上次 max seq 拉增量。
     *
     * <p><b>Response 格式</b>:
     * <pre>
     *   {
     *     "sessionId": "sid-xxx",
     *     "latestSeq": 42,
     *     "events": [
     *       {"seq": 41, "at": "2026-07-13T12:34:56Z", "type": "tool_start", "summary": "$ mvn test"},
     *       {"seq": 42, "at": "2026-07-13T12:34:59Z", "type": "tool_start", "summary": "📖 pom.xml"}
     *     ]
     *   }
     * </pre>
     *
     * <p>{@code events} 为空数组时 {@code latestSeq} 仍返当前值,前端下次可继续 since 从此。
     */
    @GetMapping("/chat/{sessionId}/events")
    public ResponseEntity<?> events(@PathVariable String sessionId,
                                    @RequestParam(defaultValue = "0") long since) {
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(error("sessionId required"));
        }
        List<TurnEvent> events = turnEventStream.since(sessionId, since);
        List<Map<String, Object>> serialized = new ArrayList<>(events.size());
        for (TurnEvent e : events) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("seq", e.seq());
            item.put("at", e.at().toString());
            item.put("type", e.type());
            item.put("summary", e.summary());
            serialized.add(item);
        }
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "latestSeq", turnEventStream.latestSeq(sessionId),
                "events", serialized));
    }

    @GetMapping("/chat/{sessionId}/pending")
    public ResponseEntity<?> pending(@PathVariable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(error("sessionId required"));
        }
        List<PendingQuestion> list = agentControl.listPending(sessionId);
        // 按 question type 展开:每种 sealed 子类扁平化自己的字段
        List<Map<String, Object>> serialized = new ArrayList<>(list.size());
        for (PendingQuestion q : list) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("askId", q.askId());
            item.put("type", q.type());
            item.put("askedAt", q.askedAt().toString());
            if (q instanceof PermissionQuestion pq) {
                item.put("toolName", pq.toolName());
                item.put("toolInput", pq.toolInput());
                item.put("reason", pq.reason());
            } else if (q instanceof ClarifyQuestion cq) {
                // s22 AQ:clarify 展平 questions —— 前端渲染选择弹框
                List<Map<String, Object>> qs = new ArrayList<>();
                for (ClarifyQuestion.SubQuestion sq : cq.questions()) {
                    List<Map<String, Object>> opts = new ArrayList<>();
                    for (ClarifyQuestion.Option op : sq.options()) {
                        Map<String, Object> optItem = new java.util.LinkedHashMap<>();
                        optItem.put("label", op.label());
                        if (op.description() != null) optItem.put("description", op.description());
                        opts.add(optItem);
                    }
                    Map<String, Object> subItem = new java.util.LinkedHashMap<>();
                    subItem.put("question", sq.question());
                    subItem.put("header", sq.header());
                    subItem.put("options", opts);
                    subItem.put("multiSelect", sq.multiSelect());
                    qs.add(subItem);
                }
                item.put("questions", qs);
            }
            serialized.add(item);
        }
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "pending", serialized));
    }

    /**
     * s22 D-10-B:回复挂起的 pending question,唤醒 agent 线程。
     *
     * <p><b>Request 格式</b>:
     * <pre>
     *   POST /api/chat/{sid}/answer
     *   {
     *     "askId": "uuid",
     *     "decision": "allow" | "deny",
     *     "reason": "..." (仅 deny 时可选)
     *   }
     * </pre>
     *
     * <p><b>返回</b>:
     * <ul>
     *   <li>200 { answered: true, sessionId, askId } —— 成功唤醒 agent</li>
     *   <li>400 —— 参数缺失 / decision 值非法</li>
     *   <li>404 —— askId 不存在(可能已 timeout / cancel / 重复 answer)</li>
     *   <li>409 —— askId 存在但 future 已完成(重复 answer,罕见但可能)</li>
     * </ul>
     */
    @PostMapping("/chat/{sessionId}/answer")
    public ResponseEntity<?> answer(@PathVariable String sessionId,
                                    @RequestBody AnswerRequest req) {
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(error("sessionId required"));
        }
        if (req == null || req.askId == null || req.askId.isBlank()) {
            return ResponseEntity.badRequest().body(error("askId required"));
        }
        if (req.decision == null) {
            return ResponseEntity.badRequest().body(error("decision required ('allow' or 'deny')"));
        }

        // 先看 pending 是否还在(区分 404 vs 409)
        if (agentControl.findPending(sessionId, req.askId).isEmpty()) {
            return ResponseEntity.status(404).body(error(
                    "askId not found (may have timed out, been cancelled, or already answered)"));
        }

        Answer answer = switch (req.decision.toLowerCase()) {
            case "allow" -> AllowAnswer.INSTANCE;
            case "deny" -> new DenyAnswer(req.reason != null && !req.reason.isBlank()
                    ? req.reason : "user rejected");
            case "choice" -> {
                // s22 AQ:clarify 型答复。前端 POST body:
                //   { askId, decision: "choice", selections: { "0": ["React"], "1": ["Yes"] } }
                if (req.selections == null || req.selections.isEmpty()) {
                    yield null;  // 参数校验:下面统一返 400
                }
                yield new ChoiceAnswer(req.selections);
            }
            default -> null;
        };
        if (answer == null) {
            return ResponseEntity.badRequest().body(error(
                    "decision must be 'allow' / 'deny' / 'choice' (with selections), got: " + req.decision));
        }

        boolean ok = agentControl.answer(sessionId, req.askId, answer);
        if (!ok) {
            // 找到过 pending 但 future 已完成(极小概率:另一个线程刚 timeout/cancel)
            return ResponseEntity.status(409).body(error(
                    "askId is no longer waiting (race with timeout/cancel)"));
        }
        log.info("[Answer] REST answered sid={} askId={} decision={}",
                sessionId, req.askId, req.decision);
        return ResponseEntity.ok(Map.of(
                "answered", true,
                "sessionId", sessionId,
                "askId", req.askId));
    }

    /** JSON DTO for {@link #answer(String, AnswerRequest)}. */
    public static class AnswerRequest {
        public String askId;
        public String decision;
        public String reason;
        /** s22 AQ:clarify 场景,{@code decision="choice"} 时必填。 key=question index, value=选中 labels。 */
        public java.util.Map<String, java.util.List<String>> selections;
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