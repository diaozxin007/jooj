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

    /** Nag 阈值:连续多少轮 LLM 调用没有 todo_write,就注入 reminder。 */
    private static final int NAG_THRESHOLD = 3;

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
                            JoojProperties props) {
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
        // 全局副作用(todoStore)— 切到任何 session 都重置一次
        onNewSession(sid -> todoStore.clear());
    }

    // ── 核心 Agent Loop ─────────────────────────────────────────

    public void agentLoop(List<MessageParam> messages) {
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
            if (roundsSinceTodo >= NAG_THRESHOLD && !messages.isEmpty()) {
                String nagText = "<reminder>You haven't updated your todos for " + NAG_THRESHOLD +
                        " rounds. Use todo_write to update task statuses.</reminder>";
                appendNagToLastUserMessage(messages, nagText);
                log.info("[Loop] nag reminder injected after {} rounds without todo_write", roundsSinceTodo);
                roundsSinceTodo = 0;
            }

            compactPipeline.apply(messages);

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
                    String bgId = bgManager.start(toolUse.getId(), command,
                            () -> registry.execute(new ToolCall(toolUse.getName(), args)));
                    String placeholder = "[Background task " + bgId + " started] " +
                            "Result will be available when complete.";
                    System.out.println("\033[35m" + placeholder + "\033[0m");
                    toolResults.add(ToolResultBlock.ofText(toolUse.getId(), placeholder));
                    continue;
                }

                ToolResultBlock result = executeOneTool(toolUse, args);
                hooks.triggerPostToolUse(toolUse, result.getContent().toString());

                if (TODO_TOOL_NAME.equals(toolUse.getName())) {
                    roundsSinceTodo = 0;
                }

                toolResults.add(result);
            }

            List<TextBlock> notifications = bgManager.drainNotifications();
            if (!notifications.isEmpty()) {
                log.info("[BG] injected {} task_notification(s) into next turn", notifications.size());
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
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        List<MessageParam> history = sessionService.loadHistory(sessionId);
        String enriched = query;
        String injection = memoryService.loadRelevant(history);
        if (injection != null && !injection.isBlank()) {
            enriched = injection + "\n\n" + query;
            log.info("[Memory] injected {} chars of relevant memories", injection.length());
        }
        history.add(MessageParam.user(enriched));
        agentLoop(history);

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
    public void processCronTriggers(List<CronJob> firedJobs) {
        if (firedJobs == null || firedJobs.isEmpty()) return;
        String sessionId = Session.CRON_DEFAULT_ID;
        List<MessageParam> history = sessionService.loadHistory(sessionId);
        for (CronJob job : firedJobs) {
            history.add(MessageParam.user("[Scheduled] " + job.getPrompt()));
            log.info("[Cron] injected fired job {} prompt into history", job.getId());
        }
        agentLoop(history);
        memoryService.onTurnEnd(history);
        sessionService.saveHistory(sessionId, history);
    }

    private void printToolHeader(ToolUseBlock toolUse, Map<String, Object> args) {
        Object cmd = args.get("command");
        String display = cmd != null ? cmd.toString() : args.toString();
        System.out.println("\033[33m$ " + display + "\033[0m");
    }

    private ToolResultBlock executeOneTool(ToolUseBlock toolUse, Map<String, Object> args) {
        ToolResult result = registry.execute(new ToolCall(toolUse.getName(), args));
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

    public void repl() {
        System.out.println("s01: Agent Loop (Java)");
        System.out.println("输入问题,回车发送。输入 q 退出。\n");

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
