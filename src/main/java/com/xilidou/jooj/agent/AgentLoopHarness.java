package com.xilidou.jooj.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.cron.CronJob;
import com.xilidou.jooj.cron.CronService;
import com.xilidou.jooj.session.AgentLockProvider;
import com.xilidou.jooj.session.HistoryScrubber;
import com.xilidou.jooj.session.Session;
import com.xilidou.jooj.session.SessionService;
import com.xilidou.jooj.team.Message;
import com.xilidou.jooj.team.MessageBus;
import com.xilidou.jooj.team.ProtocolRegistry;
import com.xilidou.jooj.tool.ToolRegistry;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ExecutionContext;
import com.xilidou.jooj.compact.CompactPipeline;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import com.xilidou.jooj.http.dto.ContentBlock;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import com.xilidou.jooj.hook.HookManager;
import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;
import com.xilidou.jooj.llm.domain.LlmRole;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolDef;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import com.xilidou.jooj.memory.MemoryService;
import com.xilidou.jooj.prompt.SystemPromptAssembler;
import com.xilidou.jooj.todo.TodoStore;
import com.xilidou.jooj.transcript.AssistantResponseCompleted;
import com.xilidou.jooj.transcript.TurnInterrupted;
import com.xilidou.jooj.transcript.UserMessageReceived;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

/**
 * AgentLoopHarness - s01_agent_loop.py 的 Java 实现。
 *
 * <p>核心模式(loop 永远不变,是 agent 的核心):
 * <pre>
 *   while stop_reason == "tool_use":
 *       response = LLM(messages, tools)
 *       execute tools
 *       append results
 * </pre>
 *
 * <h3>切片 C 设计 — 单一构造器,无循环依赖</h3>
 *
 * <p>本类只暴露 <b>一个</b> 构造器,完全由 Spring 容器装配。
 * 测试不再通过 {@code new AgentLoopHarness(...)} 直接构造,而是走
 * {@code @SpringBootTest + @Import(JoojTestConfig.class)} —— 让
 * Spring 测试框架接管依赖装配,保持生产代码"只为生产场景服务"的纯净。
 *
 * <h3>{@code task} 工具回归 Tool 接口(s12 Stage 1)</h3>
 *
 * <p>R4 重构(2026-06-24)曾把 {@code task} 工具内联到本类,通过"结构性消除"
 * 打破三方循环依赖 {@code TaskTool → Subagent → ToolRegistry → List<Tool>}。
 *
 * <p>s12 Stage 1 把 {@code task} 抽回 {@link com.xilidou.jooj.tool.impl.TaskTool}
 * 标准 Tool 实现,通过 {@code @Lazy} 注入 Subagent 打破循环 ——
 * 让所有任务相关工具(s06 task + s12 五个工具)都走统一的 ToolRegistry 路径,
 * 语义一致,扩展开闭原则纯粹。详见
 * {@link com.xilidou.jooj.tool.impl.TaskTool} 的类注释。
 *
 * <h3>Session 抽象(本次 patch)</h3>
 *
 * <p>原来的全局 {@code history} 字段已被替换 —— 每个 sessionId 有自己的 history
 * (由 {@link SessionService#loadHistory} 拿到)和自己的 lock(由
 * {@link AgentLockProvider#lockFor} 拿到),不同 session 可以并行,
 * 同 session 互斥防 messages list 撞车。
 *
 * <p>典型测试模式:
 * <pre>
 *   @SpringBootTest
 *   @ActiveProfiles("test")
 *   @Import(MyTestConfig.class)
 *   class XxxTest {
 *       @Autowired AgentLoopHarness harness;
 *       @Autowired MockAnthropicClient mock;
 *       ...
 *       harness.processOneQuery("test", "hello");
 *   }
 * </pre>
 */
@Slf4j
@Component
public class AgentLoopHarness {

    private static final int MAX_TOKENS = 8000;

    /**
     * Nag 阈值:连续多少轮 LLM 调用没有 todo_write,就注入 reminder。
     *
     * <p>从 3 提到 10 —— 实战发现 3 轮太激进,LLM 会为了消炎抢先把 todo
     * 全标 completed,造成"幻觉完成"(标 completed 但根本没调实际工具)。
     * 见 s20 Demo 7 案例。
     *
     * <p>package-private 方便测试动态读。
     */
    static final int NAG_THRESHOLD = 10;

    /** todo 工具名(注入 reminder 时识别用)。 */
    private static final String TODO_TOOL_NAME = "todo_write";

    // ── 依赖(全部 final,Spring 构造器注入)──────────────────────
    private final ToolRegistry registry;
    private final ObjectMapper json;
    private final HookManager hooks;
    private final CompactPipeline compactPipeline;
    private final MemoryService memoryService;
    private final TodoStore todoStore;

    /**
     * SYSTEM prompt 运行期组装器(s10)。每轮 LLM 调用前由 {@link #agentLoop} 调用,
     * 反映当前 context(尤其是中途新写入的 memory)。
     */
    private final SystemPromptAssembler promptAssembler;

    /**
     * 错误恢复协调器(s11)。三条路径:
     * Path 1 max_tokens 升级、Path 2 prompt_too_long 触发 reactive compact、
     * Path 3 429/529 退避 + fallback model 切换。
     *
     * <p>s22 架构审查(2026-07-13):RecoveryCoordinator 自己持有 AnthropicClient +
     * default model + default max tokens 配置,harness 不再需要三个 pass-through 字段。
     */
    private final RecoveryCoordinator recoveryCoordinator;

    /**
     * s13 Background Tasks 管理器。慢操作派 daemon 线程,placeholder 立即返回给 LLM,
     * 完成后通过 {@code <task_notification>} 文本块注入下一轮。
     */
    private final BackgroundTaskManager bgManager;

    /**
     * s14 Cron Scheduler 服务。{@link #agentLoop} 顶部 drain queue,把 fired job
     * 转成 user message 注入。{@link com.xilidou.jooj.cron.CronQueueProcessor} 反向调
     * {@link #processCronTriggers} 在持锁状态下触发一轮 agent loop。
     */
    private final CronService cronService;

    /**
     * s15 MessageBus —— Lead 在 {@link #processOneQuery} 末尾 drain 一次 lead inbox,
     * 把队友汇报作为下一轮 user message 注入 history(让 LLM 在用户下一条 query 之前
     * 看到队友结果)。
     */
    private final MessageBus messageBus;

    /**
     * s16 ProtocolRegistry —— drainLeadInbox 时先路由协议响应到 registry
     * (更新 pending → approved/rejected),再把剩下的非协议消息注入 history。
     */
    private final ProtocolRegistry protocols;

    /**
     * Session 服务(本次 patch 引入)—— history per-session 加载 / 落盘。
     */
    private final SessionService sessionService;

    /**
     * Per-session lock 池(本次 patch 引入)—— 不同 session 可以并行,同 session 互斥。
     */
    private final AgentLockProvider lockProvider;

    /** 新会话回调列表(per-session 触发)。 */
    /**
     * s22 架构审查(2026-07-13):删除 {@code onNewSessionListeners} pub/sub 机制。
     *
     * <p>之前只有一个订阅者(TodoStore.clear),已改为直接监听 SessionHistoryCleared /
     * SessionDeleted 事件,不再走 harness 中转。生产代码无外部订阅者,YAGNI 原则删除。
     *
     * s22 架构审查(2026-07-13,B1 refactor):删除 {@code channelDelivererProvider}
     * 字段 + {@code processCronTriggers} / {@code deliverCronResult} 方法。
     * cron 编排搬到 {@link com.xilidou.jooj.cron.CronTurnOrchestrator};delivery 分派
     * 搬到 {@link com.xilidou.jooj.cron.CronDeliveryHandler}。harness 只保留"单 turn
     * 执行"职责,不再感知 cron / delivery。参考 Hermes {@code cron/scheduler.py}
     * 的 {@code _run_one_job + _deliver_result} 分层。
     */

    /**
     * s22 P2:Transcript 事件 publisher —— 独立 domain 记录 user↔lead-agent 对话。
     *
     * <p>只在 {@link #processOneQuery} / {@link #processCronTriggers} 两个入口发事件,
     * loop 内部 nag / continuation / drainLeadInbox 等都**不发**(D13 边界)。
     * 详细清单参考 s22 文档 §4.6。
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * s22 D-8/D-10:用户主动打断当前 turn 的控制平面。agentLoop 每轮 while 顶部 + tool 循环
     * 每次迭代前调 {@link AgentControl#consumeInterrupt(String)},true 时抛
     * {@link AgentInterruptedException} 冒泡到 processOneQuery。
     *
     * <p>REST endpoint {@code POST /api/chat/{sid}/interrupt} 反向调 requestInterrupt。
     *
     * <p>D-10-A rename:从 {@code InterruptRegistry} 上升到 {@link AgentControl} 接口,
     * 为 D-10-B(permission ask 冒泡)/ D-10-D(teammate 接入)铺路。
     */
    private final AgentControl agentControl;

    /**
     * s22 D-11:tool 执行前 push 摘要事件的观察平面 —— 用户 turn 期间 poll {@code /events}
     * 拿实时进度("正在: $ mvn test")。跟 AgentControl 分离:AgentControl 是控制平面
     * (interrupt / approval,阻塞语义),TurnEventStream 是观察平面(纯 push 非阻塞)。
     */
    private final TurnEventStream turnEventStream;

    /**
     * 唯一构造器 —— Spring 容器装配。
     *
     * <p>s21 Demo 27 review:删 {@code permissions} 入参。
     * Permission 检查走 {@link com.xilidou.jooj.hook.impl.PermissionHook}(注册到 hooks),
     * harness 自己再持 PermissionPipeline 引用从来没人用 —— 是 R2 重构 hook 化时遗留的
     * 死字段,容易让人误以为 permission 是 harness 直接检查。
     */
    public AgentLoopHarness(ToolRegistry registry,
                            @Qualifier("joojObjectMapper") ObjectMapper json,
                            HookManager hooks,
                            CompactPipeline compactPipeline,
                            MemoryService memoryService,
                            TodoStore todoStore,
                            SystemPromptAssembler promptAssembler,
                            RecoveryCoordinator recoveryCoordinator,
                            BackgroundTaskManager bgManager,
                            CronService cronService,
                            MessageBus messageBus,
                            ProtocolRegistry protocols,
                            SessionService sessionService,
                            AgentLockProvider lockProvider,
                            ApplicationEventPublisher eventPublisher,
                            AgentControl agentControl,
                            TurnEventStream turnEventStream) {
        this.registry = registry;
        this.json = json;
        this.hooks = hooks;
        this.compactPipeline = compactPipeline;
        this.memoryService = memoryService;
        this.todoStore = todoStore;
        this.promptAssembler = promptAssembler;
        this.recoveryCoordinator = recoveryCoordinator;
        this.bgManager = bgManager;
        this.cronService = cronService;
        this.messageBus = messageBus;
        this.protocols = protocols;
        this.sessionService = sessionService;
        this.lockProvider = lockProvider;
        this.eventPublisher = eventPublisher;
        this.agentControl = agentControl;
        this.turnEventStream = turnEventStream;
    }

    // ── 核心 Agent Loop ─────────────────────────────────────────

    public void agentLoop(List<LlmMessage> messages) {
        agentLoop(messages, ExecutionContext.lead());
    }

    /**
     * 老入口:只带 sessionId,自动包装成 {@link ExecutionContext#leadInSession}。
     * Demo 20 之前的调用方走这条;Demo 20 起新调用方应走带 ctx 的重载,带上 deliveryHint 等。
     */
    public void agentLoop(List<LlmMessage> messages, String sessionId) {
        ExecutionContext ctx = sessionId != null
                ? ExecutionContext.leadInSession(sessionId)
                : ExecutionContext.lead();
        agentLoop(messages, ctx);
    }

    /**
     * s21 Demo 20 主入口:整 ctx 透传到 {@link #executeOneTool} → 各 Tool。
     * 让 CronTool 等能拿到 deliveryHint freeze 进 self-describing 的 cron job。
     *
     * @param messages 当前 turn 的 history
     * @param ctx      execution context;{@link ExecutionContext#sessionId} 决定 per-session 状态
     *                 路由,{@link ExecutionContext#deliveryHint} 决定 cron job 等的 freeze 信息
     */
    public void agentLoop(List<LlmMessage> messages, ExecutionContext ctx) {
        // 提取 sessionId 给 todoStore / bgManager 等 per-session 状态服务用。
        String sessionId = ctx != null ? ctx.sessionId() : null;
        List<LlmToolDef> tools = buildTools();
        int roundsSinceTodo = 0;

        // s14: 进入 agent_loop 顶部 drain cron queue,把 fired job 转成 user message。
        List<CronJob> firedAtTop = cronService.drainQueue();
        for (CronJob job : firedAtTop) {
            messages.add(LlmMessage.userText("[Scheduled] " + job.getPrompt()));
            log.info("[Cron] injected fired job {} prompt into agent_loop top", job.getId());
        }

        // s11: per-loop 错误恢复状态机。跨 agentLoop 调用不污染。
        var recoveryState = recoveryCoordinator.newState();

        while (true) {
            // s22 D-8/D-10:每轮 turn 开始前先检查用户是否请求打断。
            if (agentControl.consumeInterrupt(sessionId)) {
                throw new AgentInterruptedException(sessionId);
            }

            // Nag 守卫(s20 Demo 7 修复):见方法内注释。
            boolean hasOpenWork = todoStore.countByStatus(sessionId, com.xilidou.jooj.todo.TodoStatus.IN_PROGRESS) > 0
                    || todoStore.countByStatus(sessionId, com.xilidou.jooj.todo.TodoStatus.PENDING) > 0;
            if (roundsSinceTodo >= NAG_THRESHOLD && !messages.isEmpty() && hasOpenWork) {
                String nagText = "<reminder>You haven't updated your todos for " + NAG_THRESHOLD +
                        " rounds. Use todo_write to update task statuses — but ONLY mark completed " +
                        "after the actual tool call(s) for that task have run.</reminder>";
                appendNagToLastUserMessage(messages, nagText);
                log.info("[Loop] nag reminder injected after {} rounds without todo_write", roundsSinceTodo);
                roundsSinceTodo = 0;
            }

            // s22 D:token-aware 触发门禁。
            compactPipeline.compressIfNeeded(messages);

            // Scrub orphan tool_call / tool_result;下沉不变量。
            List<LlmMessage> scrubbed = HistoryScrubber.scrub(messages);
            if (scrubbed != messages) {
                messages.clear();
                messages.addAll(scrubbed);
            }

            // memory catalog 全局共享;s24 refactor: 走 canonical 一步到位,
            // 不再手工桥接 wire SystemTextBlock → LlmText + CacheHint
            var canonical = promptAssembler.assembleCanonical(promptAssembler.currentContext());

            // s22 架构审查(B2):recovery 内部消化 escalate / continuation,只暴露成功 / fatal 二元。
            // P2 Step G2:messages 已是 canonical List<LlmMessage>,不再需要 bridgeMessagesToCanonical。
            LlmResponse response;
            try {
                response = recoveryCoordinator.call(
                        state -> LlmRequest.builder()
                                .model(state.getCurrentModel())
                                .system(canonical.content())
                                .systemCacheHints(canonical.hints())
                                .messages(messages)
                                .tools(tools)
                                .maxTokens(state.getCurrentMaxTokens())
                                .build(),
                        messages,
                        recoveryState
                );
            } catch (FatalRecoveryException e) {
                // ChatHistoryMapper 认 "[Error] " 前缀翻成 SYSTEM_NOTICE(ERROR)
                messages.add(LlmMessage.assistant(List.of(new LlmText("[Error] " + e.getReason()))));
                return;
            }

            // 直接 append canonical response.content(不再桥接回 wire)
            messages.add(LlmMessage.assistant(response.getContent()));

            if (!response.needsToolExecution()) {
                Optional<String> forceContinue = hooks.triggerStop(messages);
                if (forceContinue.isPresent()) {
                    messages.add(LlmMessage.userText(forceContinue.get()));
                    continue;
                }
                return;
            }

            roundsSinceTodo++;

            List<LlmToolResult> toolResults = new ArrayList<>();
            for (LlmToolCall toolCall : response.toolCalls()) {
                // s22 D-8/D-10:每个 tool 之间也检查一次。
                if (agentControl.consumeInterrupt(sessionId)) {
                    throw new AgentInterruptedException(sessionId);
                }

                // 桥接 canonical LlmToolCall → wire ToolUseBlock 供 harness 内部 helper 使用
                // (hooks / registry / TurnEventStream 等还消费 wire 类型,Step G 之后有 follow-up)
                ToolUseBlock toolUse = new ToolUseBlock(
                        toolCall.getId(), toolCall.getName(), toolCall.getInput());

                Map<String, Object> args = parseToolInput(toolUse);

                pushToolEvent(sessionId, toolUse, args);

                Optional<String> blocked = hooks.triggerPreToolUse(toolUse);
                if (blocked.isPresent()) {
                    // s23 P1b:删掉 println,permission block 信息通过 tool_result 回给 LLM,
                    // TUI 通过 TurnEventPushed 显式呈现,legacy CLI 静默(accepted regression)。
                    log.info("[Permission] blocked tool={} reason={}", toolUse.getName(), blocked.get());
                    toolResults.add(LlmToolResult.success(toolUse.getId(), blocked.get()));
                    continue;
                }

                if (BackgroundTaskManager.shouldRunBackground(toolUse.getName(), args)) {
                    Object cmd = args.get("command");
                    String command = cmd != null ? cmd.toString() : "(no command)";
                    final ExecutionContext bgCtx = ctx;
                    String bgId = bgManager.start(sessionId, toolUse.getId(), command,
                            () -> registry.execute(new ToolCall(toolUse.getName(), args), bgCtx));
                    String placeholder = "[Background task " + bgId + " started] " +
                            "Result will be available when complete.";
                    // s23 P1b:删掉 println,placeholder 已经进 tool_result 给 LLM;
                    // 前端通过 TurnEventPushed / SSE 独立看到 bg 任务启动。
                    log.info("[BgTask] started id={} sid={} tool={} cmd={}", bgId, sessionId,
                            toolUse.getName(), command);
                    toolResults.add(LlmToolResult.success(toolUse.getId(), placeholder));
                    continue;
                }

                LlmToolResult result = executeOneTool(toolUse, args, ctx);
                hooks.triggerPostToolUse(toolUse, result.getOutput());

                if (TODO_TOOL_NAME.equals(toolUse.getName())) {
                    roundsSinceTodo = 0;
                }

                toolResults.add(result);
            }

            // s20 Demo 12: drain 仅 drain 当前 session 启动的 bg。
            List<TextBlock> notifications = bgManager.drainNotifications(sessionId);
            if (!notifications.isEmpty()) {
                log.info("[BG] session={} injected {} task_notification(s) into next turn",
                        sessionId, notifications.size());
            }
            // 合并 tool results + bg notifications 成一条 TOOL 消息 + 可选的 USER text
            // (canonical shape:notifications 是纯 text notifications,放 USER 更清晰)
            List<LlmContent> toolBlocks = new ArrayList<>(toolResults.size());
            toolBlocks.addAll(toolResults);
            messages.add(new LlmMessage(LlmRole.TOOL, toolBlocks));
            if (!notifications.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (TextBlock tb : notifications) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(tb.getText());
                }
                messages.add(LlmMessage.userText(sb.toString()));
            }
        }
    }

    /** 追加 nag reminder 到最后一条 user message(或新建一条)。 */
    private void appendNagToLastUserMessage(List<LlmMessage> messages, String nag) {
        int lastIdx = messages.size() - 1;
        LlmMessage last = messages.get(lastIdx);

        // TOOL 消息(canonical)在 wire 上映射到 role=user + tool_result blocks。
        // Nag 追加进 TOOL.content 里的 LlmText,Anthropic 适配器合并同一条 role=user,
        // 不会产生 user→user 连续。
        if (last.getRole() == LlmRole.TOOL) {
            List<LlmContent> merged = new ArrayList<>(last.getContent());
            merged.add(new LlmText(nag));
            messages.set(lastIdx, new LlmMessage(LlmRole.TOOL, merged, last.getCacheHints()));
            return;
        }

        if (last.getRole() != LlmRole.USER) {
            messages.add(LlmMessage.userText(nag));
            return;
        }

        // USER role:append 到最后一个 LlmText;若没 text 则整条新加块
        List<LlmContent> merged = new ArrayList<>(last.getContent());
        for (int i = merged.size() - 1; i >= 0; i--) {
            if (merged.get(i) instanceof LlmText t) {
                merged.set(i, new LlmText(t.getText() + "\n\n" + nag));
                messages.set(lastIdx, new LlmMessage(LlmRole.USER, merged, last.getCacheHints()));
                return;
            }
        }
        merged.add(new LlmText(nag));
        messages.set(lastIdx, new LlmMessage(LlmRole.USER, merged, last.getCacheHints()));
    }

    private List<LlmToolDef> buildTools() {
        List<LlmToolDef> tools = new ArrayList<>();
        for (ToolDefinition def : registry.getAllTools()) {
            // canonical LlmToolDef.schema 是 JsonNode;InputSchema 转 JsonNode 走 mapper。
            tools.add(new LlmToolDef(
                    def.getName(),
                    def.getDescription(),
                    json.valueToTree(def.getInputSchema())));
        }
        return tools;
    }

    // ── 新会话生命周期 ──────────────────────────────────────────
    // s22 架构审查(2026-07-13):删除 onNewSession pub/sub API + fireOnNewSession
    // 触发点。唯一订阅者(TodoStore.clear)已改事件驱动直接监听 SessionHistoryCleared /
    // SessionDeleted。生产代码无外部订阅者,YAGNI 删除。
    //
    // 若未来 harness 需要通知外界"新 session 开始",直接 publish 新事件类型即可,
    // 不再依赖 harness 内部的 ArrayList 中转。
    //
    // s21 Demo 19 的 onScheduledTurnComplete listener 在 Demo 20 重写后被移除 ——
    // 改用 cron job self-describing(deliveryType + channel + peerId)+ ChannelDeliverer 接口。
    // 见 processCronTriggers 内的 switch 路由。


    /** 清空指定 session 的 history。 */
    public void clearHistory(String sessionId) {
        sessionService.clearHistory(sessionId);
    }

    /** 拿到指定 session 的 history(可变 list 引用)。 */
    public List<LlmMessage> getHistory(String sessionId) {
        return sessionService.loadHistory(sessionId);
    }

    /**
     * 跑一轮 agent_loop —— per-session 入口。
     */
    public void processOneQuery(String sessionId, String query) {
        processOneQuery(sessionId, query, null);
    }

    /**
     * s21 Demo 20:带 deliveryHint 的 processOneQuery —— InboundDispatcher 入站时调,
     * 让 (channel, peerId) 透传到工具调用,CronTool 能 freeze 进 self-describing cron job。
     *
     * @param sessionId    target session
     * @param query        user message
     * @param deliveryHint 可选,channel 入站时由 dispatcher 提供;CLI / Web / cron-default 时 null
     */
    public void processOneQuery(String sessionId, String query, ExecutionContext.DeliveryHint deliveryHint) {
        processOneQuery(sessionId, query, deliveryHint, null);
    }

    /**
     * s22 架构审查(2026-07-13):加 {@code sourceOverride} 参数版本 —— 让 caller
     * (比如 {@code CronTurnOrchestrator})显式指定 transcript 的 source 前缀,
     * 而不是靠 harness 从 hint 里推断。这样"cron 触发" 这类语义化 source 由 caller 决定,
     * harness 保持 turn-only 职责。
     *
     * <p>合并 D7 事件类型:cron 场景现在也发 {@link UserMessageReceived},靠 source
     * 前缀({@code "cron:jobId"})跟正常 user 请求({@code "web"} / {@code "channel:xxx"})
     * 区分。删除了原来的 {@code ScheduledPromptFired} 独立事件类型 —— Hermes 参考实现
     * 证明"单一 turn 入口 + 单一入口事件"更清晰。
     *
     * @param query          送给 LLM 的 prompt(可能含 {@code [Scheduled] } 之类前缀
     *                       帮 model 理解上下文)
     * @param deliveryHint   可选,channel 入站时由 dispatcher 提供
     * @param sourceOverride 非 null 时直接作为 source;null 时按旧逻辑从 hint 派生
     *                       ({@code hint != null → "channel:" + channel},否则 {@code "session"})
     */
    public void processOneQuery(String sessionId, String query,
                                ExecutionContext.DeliveryHint deliveryHint,
                                String sourceOverride) {
        processOneQuery(sessionId, query, deliveryHint, sourceOverride, null);
    }

    /**
     * 完整版:同时可指定 {@code transcriptContent} —— transcript 里落的内容
     * 跟 {@code query} 分离。用于 cron 场景:LLM 看到 {@code "[Scheduled] check deploy"},
     * transcript 里干净落 {@code "check deploy"}。
     *
     * @param transcriptContent 非 null 时,transcript 里 UserMessageReceived.content 用它;
     *                          null 时用 {@code query}(user/web/channel 场景默认走这条)
     */
    public void processOneQuery(String sessionId, String query,
                                ExecutionContext.DeliveryHint deliveryHint,
                                String sourceOverride,
                                String transcriptContent) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }

        // s22 P2:一进门发 user 事件(干净原文,不带任何 memory/skill 前缀污染)。
        // 详细语义:s22 文档 §4.6 边界清单。source 反映入口来源,便于前端渲染 + 审计。
        String source = sourceOverride != null
                ? sourceOverride
                : (deliveryHint != null ? "channel:" + deliveryHint.channel() : "session");
        String eventContent = transcriptContent != null ? transcriptContent : query;
        eventPublisher.publishEvent(new UserMessageReceived(
                UUID.randomUUID(), sessionId, eventContent, Instant.now(), source));

        List<LlmMessage> history = sessionService.loadHistory(sessionId);
        String enriched = query;
        // s22 P3-a:传 cleanQuery 优先做召回,避免倒扫 history 时吃到上轮的 memory prefix 污染。
        // Memory 全局共享(Demo 13:1-user 假设下 user 长期事实跨会话可见)
        String injection = memoryService.loadRelevant(history, query);
        if (injection != null && !injection.isBlank()) {
            enriched = injection + "\n\n" + query;
            log.info("[Memory] injected {} chars of relevant memories", injection.length());
        }
        history.add(LlmMessage.userText(enriched));
        int historyBefore = history.size();

        // s21 Demo 20: 构造完整 ctx(sessionId + deliveryHint),透传到 agent loop
        ExecutionContext ctx = deliveryHint != null
                ? ExecutionContext.leadInChannel(sessionId, deliveryHint.channel(), deliveryHint.peerId())
                : ExecutionContext.leadInSession(sessionId);

        boolean interrupted = false;
        // s22 D-10-C:把 sid push 到 ThreadLocal,让深层 Hook / UserApprover 能拿到,
        // 不用改所有 Hook 契约。try/finally 严格恢复,防止 ThreadLocal 泄漏到下一次 processOneQuery。
        String prevSid = SessionContext.push(sessionId);
        // s22 D-12-e:同样 push channel + peerId(deliveryHint 非空时),
        // 让 AskUserQuestionTool / PermissionManager 能拿到 (channel, peerId),
        // 生成的 PendingQuestion 带 origin 信息 → WeixinAnswerPresenter 能定向送达。
        // 传 null 也 OK —— ChannelPeer 只是快照,pop 严格恢复上一层。
        SessionContext.ChannelPeer prevChannel = SessionContext.pushChannel(
                deliveryHint != null ? deliveryHint.channel() : null,
                deliveryHint != null ? deliveryHint.peerId() : null);
        try {
            try {
                agentLoop(history, ctx);
            } catch (AgentInterruptedException aie) {
                // s22 D-8:用户主动打断 —— 把 partial state 落盘,发中断事件,不进入
                // 正常的 memoryService.onTurnEnd / drainLeadInbox / AssistantResponseCompleted 流程。
                // messages 保留 partial(可能包含最后一次 assistant response),LLM 下一轮能看到"上轮被打断"上下文。
                interrupted = true;
                history.add(LlmMessage.userText("[Interrupted by user]"));
                log.info("[Interrupt] turn interrupted sid={} history_size={}", sessionId, history.size());
            }
        } finally {
            SessionContext.popChannel(prevChannel);
            SessionContext.pop(prevSid);
        }

        memoryService.onTurnEnd(history);

        // 末尾 drain lead inbox(s15)—— D13:这里 add 的 user 消息不发事件,是 loop 内部行为
        drainLeadInbox(history);

        // 保存 history 到盘
        sessionService.saveHistory(sessionId, history);

        // s22 P2:发 assistant 事件 —— 只取纯文本 final reply,不含 tool 中间态(D3)。
        // 若 assistant 只调工具没文本,reply 为 blank,TranscriptService 会跳过 append。
        String reply = lastAssistantTextSince(history, historyBefore);
        if (interrupted) {
            // s22 D-8:打断路径独占事件类型,前端可以按 role="interrupted" 渲染系统气泡。
            // partialContent 为空时前端只渲染"[已中断]"标记,非空时可以附上"这是打断前的部分回答"。
            eventPublisher.publishEvent(new TurnInterrupted(
                    UUID.randomUUID(), sessionId,
                    reply != null && !reply.isBlank() ? reply : "",
                    Instant.now()));
        } else if (reply != null && !reply.isBlank()) {
            eventPublisher.publishEvent(new AssistantResponseCompleted(
                    UUID.randomUUID(), sessionId, reply, Instant.now()));
        }

        // s22 D-11:turn 结束清 event stream,防止跨 turn 累积(前端 poll 到就渲染就够)
        turnEventStream.clear(sessionId);
    }

    /** 供 CronTurnOrchestrator 拿 reply —— 用于 delivery 后续处理。 */
    public String extractLastAssistantText(String sessionId) {
        return lastAssistantTextSince(sessionService.loadHistory(sessionId), 0);
    }

    // ────────────────────────────────────────────────────────────
    //  s22 架构审查(2026-07-13, B1 refactor):cron 编排 + delivery 已搬走
    // ────────────────────────────────────────────────────────────
    //
    // 曾经在此处的 processCronTriggers(List<CronJob>) + deliverCronResult(job, reply)
    // 现已迁移:
    //   - 编排  → com.xilidou.jooj.cron.CronTurnOrchestrator#processFired
    //   - 分派  → com.xilidou.jooj.cron.CronDeliveryHandler#deliver
    //
    // 好处:
    //   1. AgentLoopHarness 只保留"单 turn 执行"职责,不再感知 cron / delivery
    //   2. 删掉 channelDelivererProvider 依赖(harness 少 1 参)
    //   3. Cron 走"每 job 一次 processOneQuery",跟 user 场景**单一入口**,
    //      对齐 Hermes cron/scheduler.py:_run_one_job -> agent.run_conversation
    //   4. 事件语义:cron 走 UserMessageReceived + source="cron:jobId"(非 ScheduledPromptFired)
    //   5. Per-session lock 现在真的按 job.sessionId 抢,不再统一 cron-default lock
    //
    // 参考:AI Agent 实战/Week10_Skills_MCP_协议/s22_改造规划_事件驱动Transcript.md
    //       + Hermes agent 源码 cron/scheduler.py

    /**
     * 从 sinceIndex 之后的 history 里找最后一条 assistant 文本。
     * 跳过 tool_use / thinking,只回纯文本。
     * 跟 InboundDispatcher.lastAssistantText 一致语义,这里独立放在 harness 包不需要跨包依赖。
     */
    private String lastAssistantTextSince(List<LlmMessage> history, int sinceIndex) {
        // s22 P6:sinceIndex 可能被压缩过程摧毁 —— agentLoop 内部若触发
        // CompactPipeline 会削掉 history 中段,原本记录的 sinceIndex(压缩前的 size)
        // 会大于新的 history.size(),导致 for 循环从来不进入,event 丢失。
        // clamp 到 [0, history.size()-1]。
        int from = Math.max(0, Math.min(sinceIndex, history.size() - 1));
        for (int i = history.size() - 1; i >= from; i--) {
            LlmMessage m = history.get(i);
            if (m.getRole() != LlmRole.ASSISTANT || m.getContent() == null) continue;
            StringBuilder sb = new StringBuilder();
            for (LlmContent c : m.getContent()) {
                if (c instanceof LlmText t && t.getText() != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t.getText());
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        return null;
    }

    /**
     * s22 D-11:tool 执行前把摘要 push 到 {@link TurnEventStream},前端 poll /events
     * 拿到并实时更新 loading 气泡("正在: $ mvn test")。
     *
     * <p>用 {@link ToolRegistry#getTool} 找具体 Tool 实例调 {@link com.xilidou.jooj.tool.Tool#summary}
     * ——每个 tool 自己决定摘要格式(BashTool → "$ cmd";FileSystemTool → "📖 path" 等)。
     * 找不到工具时 fallback 到 toolUse.getName();不抛异常。
     */
    private void pushToolEvent(String sessionId, ToolUseBlock toolUse, Map<String, Object> args) {
        if (sessionId == null) return;
        try {
            com.xilidou.jooj.tool.Tool tool = registry.getTool(toolUse.getName());
            String summary = tool != null
                    ? tool.summary(new ToolCall(toolUse.getName(), args))
                    : toolUse.getName();
            turnEventStream.push(sessionId, TurnEvent.toolStart(summary));
        } catch (Throwable t) {
            // 摘要不该挡 tool 执行 —— 出错 log 一下继续
            log.debug("[TurnEvent] push failed for {}: {}", toolUse.getName(), t.toString());
        }
    }

    private LlmToolResult executeOneTool(ToolUseBlock toolUse, Map<String, Object> args, ExecutionContext ctx) {
        if (ctx == null) ctx = ExecutionContext.lead();
        ToolResult result = registry.execute(new ToolCall(toolUse.getName(), args), ctx);
        String output = result.getOutput();
        // s23 P1b:删掉 console preview println。tool output 已经作为 LlmToolResult 返给 LLM,
        // TUI channel 通过 TurnEventPushed(tool_use_result 事件)独立呈现;legacy CLI 静默。
        return LlmToolResult.success(toolUse.getId(), output);
    }

    private Map<String, Object> parseToolInput(ToolUseBlock toolUse) {
        try {
            Map<String, Object> converted = json.convertValue(toolUse.getInput(), new TypeReference<>() {
            });
            return converted != null ? converted : new HashMap<>();
        } catch (Exception e) {
            log.error("Failed to parse tool input for {}: {}", toolUse.getName(), e.getMessage());
            return new HashMap<>();
        }
    }

    // ── 交互式 REPL ──────────────────────────────────────────────

    /**
     * s15: drain lead 的 inbox,把队友消息揉成一条 user message 加到 history。
     */
    private void drainLeadInbox(List<LlmMessage> history) {
        List<Message> inbox = messageBus.readInbox("lead");
        if (inbox.isEmpty()) return;

        List<Message> nonProtocol = new ArrayList<>();
        int routed = 0;
        for (Message m : inbox) {
            String type = m.getType();
            if ("shutdown_response".equals(type) || "plan_approval_response".equals(type)) {
                Map<String, Object> meta = m.getMetadata();
                String reqId = String.valueOf(meta.getOrDefault("request_id", ""));
                Object approveObj = meta.get("approve");
                boolean approve = approveObj instanceof Boolean b && b;
                protocols.match(type, reqId, approve);
                routed++;
                continue;
            }
            nonProtocol.add(m);
        }
        if (routed > 0) {
            log.info("[Team] routed {} protocol response(s) to registry", routed);
        }
        if (nonProtocol.isEmpty()) return;

        StringBuilder sb = new StringBuilder("[Inbox] ").append(nonProtocol.size())
                .append(" message(s) from teammates:\n");
        for (Message m : nonProtocol) {
            sb.append("  From ").append(m.getFrom())
                    .append(" (").append(m.getType());
            String reqId = String.valueOf(m.getMetadata().getOrDefault("request_id", ""));
            if (!reqId.isBlank()) sb.append(" req:").append(reqId);
            sb.append("): ").append(m.getContent()).append('\n');
        }
        history.add(LlmMessage.userText(sb.toString()));
        log.info("[Team] drained {} non-protocol message(s) from lead inbox into history",
                nonProtocol.size());
    }
}
