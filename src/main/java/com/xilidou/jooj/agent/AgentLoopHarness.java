package com.xilidou.jooj.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojProperties;
import com.xilidou.jooj.cron.CronJob;
import com.xilidou.jooj.cron.CronService;
import com.xilidou.jooj.team.Message;
import com.xilidou.jooj.team.MessageBus;
import com.xilidou.jooj.team.ProtocolRegistry;
import com.xilidou.jooj.tool.ToolRegistry;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.compact.CompactPipeline;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import com.xilidou.jooj.http.AnthropicClient;
import com.xilidou.jooj.http.AnthropicException;
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
 * <p>典型测试模式:
 * <pre>
 *   @SpringBootTest
 *   @ActiveProfiles("test")
 *   @Import(MyTestConfig.class)
 *   class XxxTest {
 *       @Autowired AgentLoopHarness harness;
 *       @Autowired MockAnthropicClient mock;
 *       ...
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
     * 转成 user message 注入。{@link CronQueueProcessor} 反向调
     * {@link #processCronTriggers} 在持锁状态下触发一轮 agent loop。
     */
    private final CronService cronService;

    /**
     * s15 MessageBus —— Lead 在 {@link #processOneQuery} 末尾 drain 一次 lead inbox,
     * 把队友汇报作为下一轮 user message 注入 history(让 LLM 在用户下一条 query 之前
     * 看到队友结果)。
     *
     * <p>跟上游 s15 教学版一致 —— Lead 一轮跑完才看队友消息(对应 Q2 = 选项 a)。
     * Real CC 用后台 1s poller(useInboxPoller)自动注入新 turn,jooj 暂不做。
     */
    private final MessageBus messageBus;

    /**
     * s16 ProtocolRegistry —— drainLeadInbox 时先路由协议响应到 registry
     * (更新 pending → approved/rejected),再把剩下的非协议消息注入 history。
     *
     * <p>对应上游 {@code consume_lead_inbox(route_protocol=True)}。
     */
    private final ProtocolRegistry protocols;

    /**
     * s14 agentLock —— REPL user-input 流程跟 {@code CronQueueProcessor} 共享同一把锁,
     * 防 cron-fired turn 跟 user-input turn 撞 messages list。
     */
    private final ReentrantLock agentLock;

    /** 新会话回调列表。{@link #repl} 接到新 user 输入时会依次执行。 */
    private final List<Runnable> onNewSessionListeners = new ArrayList<>();

    /** REPL 多轮 history(跨会话清理由 onNewSession 注册)。 */
    private final List<MessageParam> history = new ArrayList<>();

    /**
     * 唯一构造器 —— Spring 容器装配。
     *
     * <p>{@code @Qualifier} 在 {@code json} 上让 Spring 解析到我们自己注册的
     * {@code joojObjectMapper},而不是 Spring Boot 自带的 jackson auto-config 主 mapper。
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
                            @Qualifier("agentLock") ReentrantLock agentLock,
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
        this.agentLock = agentLock;
        this.recoveryCfg = props.getRecovery();
    }

    /**
     * Spring 装配完成后注册 {@code onNewSession} 回调(清空 todoStore + history)。
     *
     * <p>s10 之后:不再在这里计算 systemPrompt —— 改由 {@link SystemPromptAssembler}
     * 在每轮 LLM 调用前按当前 context 动态组装,这样新写入的 memory 立刻能进 SYSTEM。
     */
    @PostConstruct
    void init() {
        onNewSession(todoStore::clear);
        // 清空跨会话 history,与 todoStore::clear 一起补齐"新会话边界"两半。
        onNewSession(this::clearHistory);
    }

    // ── 核心 Agent Loop ─────────────────────────────────────────

    public void agentLoop(List<MessageParam> messages) {
        List<ToolDef> tools = buildTools();
        int roundsSinceTodo = 0;

        // s14: 进入 agent_loop 顶部 drain cron queue,把 fired job 转成 user message。
        // 注:CronQueueProcessor 在拿到 agentLock 后调 processCronTriggers,
        // 那条路径已经把 prompt 注入 messages 再 agentLoop;这里 drain 是 belt-and-suspenders,
        // 防 user-input 流程刚好夹在两次 fire 之间也能消费已 fired 的 prompt。
        List<CronJob> firedAtTop = cronService.drainQueue();
        for (CronJob job : firedAtTop) {
            messages.add(MessageParam.user("[Scheduled] " + job.getPrompt()));
            log.info("[Cron] injected fired job {} prompt into agent_loop top", job.getId());
        }

        // s11: per-loop 错误恢复状态机。跨 agentLoop 调用不污染。
        // 初始 model = 配置里的默认 model;初始 max_tokens = recovery.defaultMaxTokens
        // (Path 1 触发时 currentMaxTokens 会被升级到 escalatedMaxTokens)。
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

            // s10: 每轮 LLM 调用前按当前 context 组装 SYSTEM。
            // 这是修复 turn N 写入 memory → turn N+1 SYSTEM 看不见 的关键 ——
            // assembler 内部 size=1 缓存,context 不变时 0 成本。
            //
            // assembleBlocks 拆成两段:
            //   Block 1: identity + tools + workspace (启动后不变,加 cache_control)
            //   Block 2: memory (易变,不加 cache_control)
            // 命中时 Block 1 跳过 prefill,memory 写入只重写 Block 2。
            // 详见 SystemPromptAssembler.assembleBlocks 的 javadoc。
            var system = promptAssembler.assembleBlocks(promptAssembler.currentContext());

            // s11: 调 LLM + 三条恢复路径(429/529 退避 + max_tokens 升级 +
            // prompt_too_long reactive compact),封装在 RecoveryCoordinator 里。
            // requestBuilder 是 lambda,因为 retry 中 state.currentModel /
            // state.currentMaxTokens 可能被 mutate,request 必须每次用最新 state 重建。
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
                // Path 1 第一次升级 / Path 2 reactive compact 后,直接重新跑循环
                continue;
            } else if (recoveryResult instanceof RecoveryResult.AppendContinuation ac) {
                // Path 1 已升级仍截断 → append 截断输出 + continuation user message
                messages.add(MessageParam.assistant(ac.response().getContent()));
                messages.add(MessageParam.user(ac.continuation()));
                continue;
            } else if (recoveryResult instanceof RecoveryResult.Fatal f) {
                // 不可恢复:把错误说明追加到对话,让 REPL 打出来给用户看
                messages.add(MessageParam.assistant(List.of(
                        new TextBlock("[Error] " + f.reason()))));
                return;
            } else {
                // sealed interface 4 个分支已穷尽,理论不可达;留个兜底
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

                // s13: 决定本次工具调用走前台还是后台。
                // - LLM 显式 run_in_background=true 优先
                // - 否则启发式:bash + 慢操作关键词命中 → 后台
                // 后台路径不接 PostToolUse hook —— hook 是同步对前台结果的反应,
                // 后台完成后通过 task_notification 注入,LLM 自己消费(跟上游一致)。
                //
                // 线程重构 Stage 3:bg 池用 CallerRunsPolicy,满则 caller 线程同步跑,
                // BackgroundTaskManager.start 不会抛 RejectedExecutionException,
                // 不需要 try-catch fallback。
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

            // s13: drain 已完成的 bg task 通知,跟本轮 tool_results 合并到同一条 user message。
            // notifications 为空时 toolResultsWithNotifications 退化为 toolResults,行为不变。
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

    /**
     * 把 {@link ToolRegistry} 里的工具转成 Anthropic 协议 ToolDef。
     *
     * <p>s12 Stage 1 之后,{@code task} 工具是 {@link com.xilidou.jooj.tool.impl.TaskTool}
     * 的标准实现,自动出现在 {@code registry.getAllTools()} 里 —— 不再需要手动追加。
     */
    private List<ToolDef> buildTools() {
        List<ToolDef> tools = new ArrayList<>();
        for (ToolDefinition def : registry.getAllTools()) {
            tools.add(new ToolDef(def.getName(), def.getDescription(), def.getInputSchema()));
        }
        return tools;
    }

    // ── 新会话生命周期 ──────────────────────────────────────────

    public AgentLoopHarness onNewSession(Runnable callback) {
        if (callback != null) {
            onNewSessionListeners.add(callback);
        }
        return this;
    }

    private void fireOnNewSession() {
        for (Runnable callback : onNewSessionListeners) {
            try {
                callback.run();
            } catch (Exception e) {
                log.warn("[Loop] onNewSession callback failed: {}", e.getMessage());
            }
        }
    }

    public void clearHistory() {
        history.clear();
    }

    public List<MessageParam> getHistory() {
        return history;
    }

    public void processOneQuery(String query) {
        String enriched = query;
        String injection = memoryService.loadRelevant(history);
        if (injection != null && !injection.isBlank()) {
            enriched = injection + "\n\n" + query;
            log.info("[Memory] injected {} chars of relevant memories", injection.length());
        }
        history.add(MessageParam.user(enriched));
        agentLoop(history);

        memoryService.onTurnEnd(history);

        // s15: 末尾 drain lead inbox —— 把队友(spawn 出去的 daemon)发给 lead 的消息
        // 揉成一条 user message 加到 history,**不立即跑下一轮 agent_loop**(对齐上游教学版)。
        // 用户下次 query 进来时,LLM 看到的就是"上一轮我自己 + 队友们的回复"组合 context。
        drainLeadInbox();
    }

    /**
     * s14: 由 {@link com.xilidou.jooj.cron.CronQueueProcessor} 在持有 agentLock 后调用。
     *
     * <p>把 fired CronJob 的 prompt 各注入成一条 user message,然后跑一轮 agent_loop。
     * <b>不</b>调 {@code fireOnNewSession} —— cron 触发不算新会话,history 不应被清。
     *
     * <p>跟 user-input 流程的差别:
     * <ul>
     *   <li>不走 hooks.triggerUserPrompt(那是 user 主动键入时才触发)</li>
     *   <li>不走 memoryService.loadRelevant(cron prompt 通常不需要 memory 注入)</li>
     *   <li>仍走 memoryService.onTurnEnd(让 cron 完成后能更新 memory)</li>
     * </ul>
     *
     * @param firedJobs 已 drain 的 CronJob 列表
     */
    public void processCronTriggers(List<CronJob> firedJobs) {
        if (firedJobs == null || firedJobs.isEmpty()) return;
        for (CronJob job : firedJobs) {
            history.add(MessageParam.user("[Scheduled] " + job.getPrompt()));
            log.info("[Cron] injected fired job {} prompt into history", job.getId());
        }
        agentLoop(history);
        memoryService.onTurnEnd(history);
    }

    private void printToolHeader(ToolUseBlock toolUse, Map<String, Object> args) {
        Object cmd = args.get("command");
        String display = cmd != null ? cmd.toString() : args.toString();
        System.out.println("\033[33m$ " + display + "\033[0m");
    }

    /**
     * 派发一次工具调用 —— 统一走 {@link ToolRegistry}。
     *
     * <p>s12 Stage 1 之后,{@code task} 工具回归 {@link com.xilidou.jooj.tool.impl.TaskTool}
     * 标准实现,这里不再需要 task 特判。
     */
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

                // s14: 拿 agentLock —— 跟 CronQueueProcessor 共享。
                // user-input 流程跟 cron-fired 流程互斥,防 messages list 撞车。
                // tryLock 失败说明 cron 流程刚好在跑,等下一轮(用户重输即可)。
                if (!agentLock.tryLock()) {
                    System.out.println("\033[33m⏳ Agent busy with a scheduled task, please retry.\033[0m");
                    continue;
                }
                try {
                    fireOnNewSession();
                    processOneQuery(query);
                } finally {
                    agentLock.unlock();
                }

                printLastAssistantText(history);
                System.out.println();
            }
        }
    }

    /**
     * s15: drain lead 的 inbox,把队友消息揉成一条 user message 加到 history。
     *
     * <p>仅 append 不跑 loop —— 对齐上游 s15 教学版的决策(Q2-a):
     * 用户下次 query 来时,LLM 看到的 context 自然包含队友消息;Lead 不为
     * 队友消息单独跑一轮(那是 real CC 的 useInboxPoller 行为,留给后续 stage)。
     *
     * <p>history 因为这一调用可能形成 "...assistant, user(inbox)" 末尾,
     * 下次 processOneQuery 进来时 history 末尾还会再 add 一条 user(query)——
     * Anthropic 协议允许连续两条 user message。
     *
     * <p>s16 升级:**先把协议响应路由到 ProtocolRegistry**(更新 pending →
     * approved/rejected),再把剩下的非协议消息注入 history。
     * 跟上游 {@code consume_lead_inbox(route_protocol=True)} 一致 ——
     * 防止协议响应被消费但 registry 状态没更新的 bug。
     */
    private void drainLeadInbox() {
        List<Message> inbox = messageBus.readInbox("lead");
        if (inbox.isEmpty()) return;

        // s16: 先路由协议响应,从 inbox 列表中摘出去
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
            // 协议请求(plan_approval_request)的 request_id 显式给 LLM 看,方便 review_plan
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
