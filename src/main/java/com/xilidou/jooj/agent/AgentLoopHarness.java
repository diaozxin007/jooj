package com.xilidou.jooj.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojProperties;
import com.xilidou.jooj.cron.CronJob;
import com.xilidou.jooj.cron.CronService;
import com.xilidou.jooj.session.AgentLockProvider;
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
import com.xilidou.jooj.http.AnthropicClient;
import com.xilidou.jooj.http.dto.ContentBlock;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ToolDef;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import com.xilidou.jooj.hook.HookManager;
import com.xilidou.jooj.memory.MemoryService;
import com.xilidou.jooj.permission.PermissionPipeline;
import com.xilidou.jooj.prompt.SystemPromptAssembler;
import com.xilidou.jooj.todo.TodoStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

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

    /** 工具结果输出在屏幕上的截断长度。 */
    private static final int CONSOLE_PREVIEW_LIMIT = 200;

    // ── 依赖(全部 final,Spring 构造器注入)──────────────────────
    private final AnthropicClient client;
    private final String model;
    private final ToolRegistry registry;
    private final ObjectMapper json;
    private final PermissionPipeline permissions;
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
     */
    private final RecoveryCoordinator recoveryCoordinator;

    /** 错误恢复配置(s11)。{@link #agentLoop} 用 {@code defaultMaxTokens} 初始化 RecoveryState。 */
    private final JoojProperties.Recovery recoveryCfg;

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
    private final List<Consumer<String>> onNewSessionListeners = new ArrayList<>();

    /**
     * Channel 投递抽象 —— Demo 20 起用 self-describing 路由,harness 直接调它把 cron 回复
     * 发到 channel,不再用 Demo 19 的 listener + 反查表。
     *
     * <p>{@code ObjectProvider} 让 channel 包不存在时(纯 CLI 模式)也能装配 harness,
     * deliveryType=channel 时 deliverer 缺席就 log warn + skip。
     */
    private final org.springframework.beans.factory.ObjectProvider<com.xilidou.jooj.channel.ChannelDeliverer> channelDelivererProvider;

    /**
     * 唯一构造器 —— Spring 容器装配。
     */
    public AgentLoopHarness(AnthropicClient client,
                            ToolRegistry registry,
                            @Qualifier("joojObjectMapper") ObjectMapper json,
                            PermissionPipeline permissions,
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
                            JoojProperties props,
                            org.springframework.beans.factory.ObjectProvider<com.xilidou.jooj.channel.ChannelDeliverer> channelDelivererProvider) {
        this.client = client;
        this.model = props.getAnthropic().getModel();
        this.registry = registry;
        this.json = json;
        this.permissions = permissions;
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
        this.channelDelivererProvider = channelDelivererProvider;
        this.recoveryCfg = props.getRecovery();
    }

    /**
     * Spring 装配完成后注册 {@code onNewSession} 回调(清空 todoStore)。
     * 注:history 的 clear 现在跟着 sessionId 走,不在新会话边界统一清,
     * 因为 cli-default 的"新会话"恰恰是用户希望保留的(REPL 多轮对话);
     * 真要清显式调 {@link #clearHistory}。
     */
    @PostConstruct
    void init() {
        // s20 Demo 12: todoStore 已 per-session 化。新 session 触发只清自己分区,不影响别的 session。
        onNewSession(sid -> todoStore.clear(sid));
    }

    // ── 核心 Agent Loop ─────────────────────────────────────────

    public void agentLoop(List<MessageParam> messages) {
        agentLoop(messages, ExecutionContext.lead());
    }

    /**
     * 老入口:只带 sessionId,自动包装成 {@link ExecutionContext#leadInSession}。
     * Demo 20 之前的调用方走这条;Demo 20 起新调用方应走带 ctx 的重载,带上 deliveryHint 等。
     */
    public void agentLoop(List<MessageParam> messages, String sessionId) {
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
    public void agentLoop(List<MessageParam> messages, ExecutionContext ctx) {
        // 提取 sessionId 给 todoStore / bgManager 等 per-session 状态服务用。
        String sessionId = ctx != null ? ctx.sessionId() : null;
        List<ToolDef> tools = buildTools();
        int roundsSinceTodo = 0;

        // s14: 进入 agent_loop 顶部 drain cron queue,把 fired job 转成 user message。
        List<CronJob> firedAtTop = cronService.drainQueue();
        for (CronJob job : firedAtTop) {
            messages.add(MessageParam.user("[Scheduled] " + job.getPrompt()));
            log.info("[Cron] injected fired job {} prompt into agent_loop top", job.getId());
        }

        // s11: per-loop 错误恢复状态机。跨 agentLoop 调用不污染。
        var recoveryState = new RecoveryState(model, recoveryCfg.getDefaultMaxTokens());

        while (true) {
            // Nag 守卫(s20 Demo 7 修复):
            // 1) 阈值轮数没到 → skip
            // 2) messages 为空 → skip(没有上文可挂)
            // 3) 没有任何 in_progress todo → skip(没事可催)。这是关键守卫——
            //    旧逻辑无脑 nag,LLM 为了消炎抢先把 todo 全标 completed 造成幻觉完成。
            //    只在有真正"挂着的活"时才催,反向激励 LLM 干完才标 completed。
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

            compactPipeline.apply(messages);

            // memory catalog 全局共享(Demo 13 撤销 per-session 化 —— 见 MemoryService 类注释)
            var system = promptAssembler.assembleBlocks(promptAssembler.currentContext());

            RecoveryResult recoveryResult = recoveryCoordinator.call(
                    client,
                    state -> CreateMessageRequest.builder()
                            .model(state.getCurrentModel())
                            .system(system)
                            .messages(messages)
                            .tools(tools)
                            .maxTokens(state.getCurrentMaxTokens())
                            .build(),
                    messages,
                    recoveryState
            );

            CreateMessageResponse response;
            if (recoveryResult instanceof RecoveryResult.Done d) {
                response = d.response();
            } else if (recoveryResult instanceof RecoveryResult.EscalateAndRetry) {
                continue;
            } else if (recoveryResult instanceof RecoveryResult.AppendContinuation ac) {
                messages.add(MessageParam.assistant(ac.response().getContent()));
                messages.add(MessageParam.user(ac.continuation()));
                continue;
            } else if (recoveryResult instanceof RecoveryResult.Fatal f) {
                messages.add(MessageParam.assistant(List.of(
                        new TextBlock("[Error] " + f.reason()))));
                return;
            } else {
                throw new IllegalStateException("Unhandled RecoveryResult: " + recoveryResult);
            }

            messages.add(MessageParam.assistant(response.getContent()));

            if (!response.needsToolExecution()) {
                Optional<String> forceContinue = hooks.triggerStop(messages);
                if (forceContinue.isPresent()) {
                    messages.add(MessageParam.user(forceContinue.get()));
                    continue;
                }
                return;
            }

            roundsSinceTodo++;

            List<ToolResultBlock> toolResults = new ArrayList<>();
            for (ToolUseBlock toolUse : response.toolUses()) {
                Map<String, Object> args = parseToolInput(toolUse);

                printToolHeader(toolUse, args);

                Optional<String> blocked = hooks.triggerPreToolUse(toolUse);
                if (blocked.isPresent()) {
                    System.out.println("\033[31m⛔ " + blocked.get() + "\033[0m");
                    toolResults.add(ToolResultBlock.ofText(toolUse.getId(), blocked.get()));
                    continue;
                }

                if (BackgroundTaskManager.shouldRunBackground(toolUse.getName(), args)) {
                    Object cmd = args.get("command");
                    String command = cmd != null ? cmd.toString() : "(no command)";
                    final ExecutionContext bgCtx = ctx;
                    // s20 Demo 12: bg 任务也带 sessionId,完成通知只回到本 session,
                    // 不串味给其他 session 那一轮 history。
                    // s21 Demo 20: 整个 ctx 透传(带 deliveryHint),让 bg 内 CronTool 等也能用。
                    String bgId = bgManager.start(sessionId, toolUse.getId(), command,
                            () -> registry.execute(new ToolCall(toolUse.getName(), args), bgCtx));
                    String placeholder = "[Background task " + bgId + " started] " +
                            "Result will be available when complete.";
                    System.out.println("\033[35m" + placeholder + "\033[0m");
                    toolResults.add(ToolResultBlock.ofText(toolUse.getId(), placeholder));
                    continue;
                }

                ToolResultBlock result = executeOneTool(toolUse, args, ctx);
                hooks.triggerPostToolUse(toolUse, result.getContent().toString());

                if (TODO_TOOL_NAME.equals(toolUse.getName())) {
                    roundsSinceTodo = 0;
                }

                toolResults.add(result);
            }

            // s20 Demo 12: drain 仅 drain 当前 session 启动的 bg(其他 session 的留着等他们自己 drain)。
            List<TextBlock> notifications = bgManager.drainNotifications(sessionId);
            if (!notifications.isEmpty()) {
                log.info("[BG] session={} injected {} task_notification(s) into next turn",
                        sessionId, notifications.size());
            }
            messages.add(MessageParam.toolResultsWithNotifications(toolResults, notifications));
        }
    }

    @SuppressWarnings("unchecked")
    private void appendNagToLastUserMessage(List<MessageParam> messages, String nag) {
        int lastIdx = messages.size() - 1;
        MessageParam last = messages.get(lastIdx);

        if (!"user".equals(last.getRole())) {
            messages.add(MessageParam.user(nag));
            return;
        }

        Object content = last.getContent();
        MessageParam merged;
        if (content instanceof String oldText) {
            merged = MessageParam.user(oldText + "\n\n" + nag);
        } else if (content instanceof List<?> blocks) {
            List<ContentBlock> newBlocks = new ArrayList<>((List<ContentBlock>) blocks);
            newBlocks.add(new TextBlock(nag));
            merged = new MessageParam("user", newBlocks);
        } else {
            messages.add(MessageParam.user(nag));
            return;
        }
        messages.set(lastIdx, merged);
    }

    private List<ToolDef> buildTools() {
        List<ToolDef> tools = new ArrayList<>();
        for (ToolDefinition def : registry.getAllTools()) {
            tools.add(new ToolDef(def.getName(), def.getDescription(), def.getInputSchema()));
        }
        return tools;
    }

    // ── 新会话生命周期 ──────────────────────────────────────────

    /** 注册一个 per-session 触发的回调(给 sessionId 作为参数)。 */
    public AgentLoopHarness onNewSession(Consumer<String> callback) {
        if (callback != null) {
            onNewSessionListeners.add(callback);
        }
        return this;
    }

    // s21 Demo 19 的 onScheduledTurnComplete listener 在 Demo 20 重写后被移除 ——
    // 改用 cron job self-describing(deliveryType + channel + peerId)+ ChannelDeliverer 接口。
    // 见 processCronTriggers 内的 switch 路由。

    /** 兼容老 API。回调以 {@link Session#CLI_DEFAULT_ID} 触发。 */
    public AgentLoopHarness onNewSession(Runnable callback) {
        if (callback != null) {
            onNewSessionListeners.add(sid -> callback.run());
        }
        return this;
    }

    private void fireOnNewSession(String sessionId) {
        for (Consumer<String> callback : onNewSessionListeners) {
            try {
                callback.accept(sessionId);
            } catch (Exception e) {
                log.warn("[Loop] onNewSession callback failed: {}", e.getMessage());
            }
        }
    }

    /** 清空指定 session 的 history。 */
    public void clearHistory(String sessionId) {
        sessionService.clearHistory(sessionId);
    }

    /** 拿到指定 session 的 history(可变 list 引用)。 */
    public List<MessageParam> getHistory(String sessionId) {
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
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        List<MessageParam> history = sessionService.loadHistory(sessionId);
        String enriched = query;
        // Memory 全局共享(Demo 13:1-user 假设下 user 长期事实跨会话可见)
        String injection = memoryService.loadRelevant(history);
        if (injection != null && !injection.isBlank()) {
            enriched = injection + "\n\n" + query;
            log.info("[Memory] injected {} chars of relevant memories", injection.length());
        }
        history.add(MessageParam.user(enriched));

        // s21 Demo 20: 构造完整 ctx(sessionId + deliveryHint),透传到 agent loop
        ExecutionContext ctx = deliveryHint != null
                ? ExecutionContext.leadInChannel(sessionId, deliveryHint.channel(), deliveryHint.peerId())
                : ExecutionContext.leadInSession(sessionId);
        agentLoop(history, ctx);

        memoryService.onTurnEnd(history);

        // 末尾 drain lead inbox(s15)
        drainLeadInbox(history);

        // 保存 history 到盘
        sessionService.saveHistory(sessionId, history);
    }

    /**
     * s14: 由 {@link com.xilidou.jooj.cron.CronQueueProcessor} 在持有 lock 后调用。
     *
     * <p>cron 触发的 LLM run 路由到 {@link Session#CRON_DEFAULT_ID} session,
     * 跟用户交互 session 完全隔离 —— 这是引入 session 抽象后的核心好处。
     */
    /**
     * 处理一批已 fired 的 cron job。
     *
     * <h3>s20 Demo 9 改动</h3>
     *
     * <p>旧版把所有 fired job 一律塞 {@link Session#CRON_DEFAULT_ID} 这个收容 session,
     * 用户在 web 前端的特定 session 里调度的 cron 触发后,通知永远到不了那个 session,
     * 前端轮询的 history 看不见。
     *
     * <p>现在按 {@code job.sessionId} 分组路由:有 sessionId → 注入对应 session;
     * sessionId == null(老 cron / cli REPL 调度) → 兜底 cron-default。每个 session
     * 一次 agentLoop。
     */
    public void processCronTriggers(List<CronJob> firedJobs) {
        if (firedJobs == null || firedJobs.isEmpty()) return;

        // 按 sessionId 分组(null → cron-default)
        Map<String, List<CronJob>> bySession = new java.util.LinkedHashMap<>();
        for (CronJob job : firedJobs) {
            String sid = job.getSessionId() != null ? job.getSessionId() : Session.CRON_DEFAULT_ID;
            if (!sessionService.exists(sid)) {
                log.warn("[Cron] job {} target session {} no longer exists, " +
                        "falling back to cron-default", job.getId(), sid);
                sid = Session.CRON_DEFAULT_ID;
            }
            bySession.computeIfAbsent(sid, k -> new java.util.ArrayList<>()).add(job);
        }

        for (Map.Entry<String, List<CronJob>> entry : bySession.entrySet()) {
            String sessionId = entry.getKey();
            List<CronJob> jobsThisSession = entry.getValue();
            List<MessageParam> history = sessionService.loadHistory(sessionId);
            int historyBefore = history.size();
            for (CronJob job : jobsThisSession) {
                history.add(MessageParam.user("[Scheduled] " + job.getPrompt()));
                log.info("[Cron] injected fired job {} prompt into session {}",
                        job.getId(), sessionId);
            }
            agentLoop(history, sessionId);
            memoryService.onTurnEnd(history);
            sessionService.saveHistory(sessionId, history);

            // s21 Demo 20:每个 fired job 按自己的 deliveryType 路由(self-describing)。
            // 同 session 多 jobs 共享同一段 reply(它们的 prompt 都串接进了 turn)。
            String reply = lastAssistantTextSince(history, historyBefore);
            for (CronJob job : jobsThisSession) {
                deliverCronResult(job, reply);
            }
        }
    }

    /**
     * s21 Demo 20:按 cron job 自描述的 deliveryType 路由 LLM 回复。
     * cron job 创建时已经 freeze 了路由信息,fire 时**只读 cron job 自身**,不查任何旁路状态。
     */
    private void deliverCronResult(CronJob job, String reply) {
        String type = job.getDeliveryType();
        if (type == null) type = "none";

        switch (type) {
            case "channel" -> {
                if (reply == null || reply.isBlank()) {
                    log.info("[Cron] job {} channel-delivery skipped: no assistant text", job.getId());
                    return;
                }
                String channel = job.getChannel();
                String peerId = job.getPeerId();
                if (channel == null || peerId == null) {
                    log.warn("[Cron] job {} deliveryType=channel but missing channel/peerId, dropped",
                            job.getId());
                    return;
                }
                com.xilidou.jooj.channel.ChannelDeliverer deliverer =
                        channelDelivererProvider != null ? channelDelivererProvider.getIfAvailable() : null;
                if (deliverer == null) {
                    log.warn("[Cron] job {} deliveryType=channel but no ChannelDeliverer wired " +
                            "(jooj.weixin.enabled=false?), dropped", job.getId());
                    return;
                }
                boolean ok = deliverer.deliver(channel, peerId, reply);
                log.info("[Cron] job {} delivered to channel={} peer={}: {}",
                        job.getId(), channel, peerId, ok);
            }
            case "team" -> {
                // Tier B 后续:用 messageBus 投递给 alice/bob;当前 LLM 还没自己生成 team cron
                log.warn("[Cron] job {} deliveryType=team not yet implemented", job.getId());
            }
            case "none" -> {
                log.debug("[Cron] job {} deliveryType=none, no outbound delivery", job.getId());
            }
            default ->
                log.warn("[Cron] job {} unknown deliveryType '{}', dropped", job.getId(), type);
        }
    }

    /**
     * 从 sinceIndex 之后的 history 里找最后一条 assistant 文本。
     * 跳过 tool_use / thinking,只回纯文本。
     * 跟 InboundDispatcher.lastAssistantText 一致语义,这里独立放在 harness 包不需要跨包依赖。
     */
    private String lastAssistantTextSince(List<MessageParam> history, int sinceIndex) {
        for (int i = history.size() - 1; i >= sinceIndex; i--) {
            MessageParam m = history.get(i);
            if (!"assistant".equals(m.getRole())) continue;
            Object c = m.getContent();
            if (c instanceof String s && !s.isBlank()) return s;
            if (c instanceof List<?> blocks) {
                StringBuilder sb = new StringBuilder();
                for (Object b : blocks) {
                    if (b instanceof TextBlock tb && tb.getText() != null) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(tb.getText());
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }
        }
        return null;
    }

    private void printToolHeader(ToolUseBlock toolUse, Map<String, Object> args) {
        Object cmd = args.get("command");
        String display = cmd != null ? cmd.toString() : args.toString();
        System.out.println("\033[33m$ " + display + "\033[0m");
    }

    private ToolResultBlock executeOneTool(ToolUseBlock toolUse, Map<String, Object> args, ExecutionContext ctx) {
        if (ctx == null) ctx = ExecutionContext.lead();
        ToolResult result = registry.execute(new ToolCall(toolUse.getName(), args), ctx);
        String output = result.getOutput();

        System.out.println(output.length() > CONSOLE_PREVIEW_LIMIT
                ? output.substring(0, CONSOLE_PREVIEW_LIMIT) + "..."
                : output);

        return ToolResultBlock.ofText(toolUse.getId(), output);
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
     * 老签名:不带 slash 命令支持的 REPL。保留给老调用方 / 测试。
     * 新 CLI 走 {@link #repl(com.xilidou.jooj.slashcmd.SlashCommandRegistry)}。
     */
    public void repl() {
        repl(null);
    }

    /**
     * 带 slash 命令路由的 REPL。
     *
     * <p>{@code slashCommands == null} 时退化成老行为(query 全走 LLM)。
     * 注入了 registry 时:query 以 / 开头 → 走 registry.dispatch,**不进 LLM、不进 history**。
     */
    public void repl(com.xilidou.jooj.slashcmd.SlashCommandRegistry slashCommands) {
        System.out.println("s01: Agent Loop (Java)");
        System.out.println("输入问题,回车发送。/help 查看命令,q 退出。\n");

        // CLI REPL 走固定 cli-default session,跨进程重启历史保留。
        final String sessionId = Session.CLI_DEFAULT_ID;
        ReentrantLock lock = lockProvider.lockFor(sessionId);

        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            while (true) {
                System.out.print("\033[36ms01 >> \033[0m");
                if (!scanner.hasNextLine()) break;

                String query = scanner.nextLine().strip();
                if (query.equalsIgnoreCase("q")
                        || query.equalsIgnoreCase("exit")
                        || query.isEmpty()) break;

                // Slash 命令 —— 走 registry,跳过 hooks / lock / processOneQuery。
                // 这些都是纯客户端动作,不进 LLM、不算并发请求。
                if (slashCommands != null && slashCommands.isCommand(query)) {
                    System.out.println(slashCommands.dispatch(query, sessionId));
                    System.out.println();
                    continue;
                }

                Optional<String> blocked = hooks.triggerUserPrompt(query);
                if (blocked.isPresent()) {
                    System.out.println("\033[31m⛔ Prompt blocked: " + blocked.get() + "\033[0m");
                    continue;
                }

                if (!lock.tryLock()) {
                    System.out.println("\033[33m⏳ Agent busy, please retry.\033[0m");
                    continue;
                }
                try {
                    fireOnNewSession(sessionId);
                    processOneQuery(sessionId, query);
                } finally {
                    lock.unlock();
                }

                printLastAssistantText(sessionService.loadHistory(sessionId));
                System.out.println();
            }
        }
    }

    /**
     * s15: drain lead 的 inbox,把队友消息揉成一条 user message 加到 history。
     */
    private void drainLeadInbox(List<MessageParam> history) {
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
        history.add(MessageParam.user(sb.toString()));
        log.info("[Team] drained {} non-protocol message(s) from lead inbox into history",
                nonProtocol.size());
    }

    private void printLastAssistantText(List<MessageParam> history) {
        if (history.isEmpty()) return;
        MessageParam last = history.get(history.size() - 1);
        if (!"assistant".equals(last.getRole())) return;

        Object content = last.getContent();
        if (content instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof TextBlock t) {
                    System.out.println(t.getText());
                }
            }
        } else if (content instanceof String s) {
            System.out.println(s);
        }
    }
}
