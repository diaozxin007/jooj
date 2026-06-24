package com.xilidou.marvis.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.MarvisProperties;
import com.xilidou.marvis.tool.ToolRegistry;
import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.compact.CompactPipeline;
import com.xilidou.marvis.tool.ToolDefinition;
import com.xilidou.marvis.tool.ToolResult;
import com.xilidou.marvis.http.AnthropicClient;
import com.xilidou.marvis.http.AnthropicException;
import com.xilidou.marvis.http.dto.ContentBlock;
import com.xilidou.marvis.http.dto.CreateMessageRequest;
import com.xilidou.marvis.http.dto.CreateMessageResponse;
import com.xilidou.marvis.http.dto.InputSchema;
import com.xilidou.marvis.http.dto.MessageParam;
import com.xilidou.marvis.http.dto.TextBlock;
import com.xilidou.marvis.http.dto.ToolDef;
import com.xilidou.marvis.http.dto.ToolResultBlock;
import com.xilidou.marvis.http.dto.ToolUseBlock;
import com.xilidou.marvis.hook.HookManager;
import com.xilidou.marvis.memory.MemoryService;
import com.xilidou.marvis.permission.PermissionPipeline;
import com.xilidou.marvis.prompt.SystemPromptAssembler;
import com.xilidou.marvis.subagent.Subagent;
import com.xilidou.marvis.todo.TodoStore;
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
 * {@code @SpringBootTest + @Import(MarvisTestConfig.class)} —— 让
 * Spring 测试框架接管依赖装配,保持生产代码"只为生产场景服务"的纯净。
 *
 * <h3>{@code task} 工具内联在本类</h3>
 *
 * <p>原 {@code TaskTool} 类(实现 {@code Tool},被 {@code @Component} 收集)已删除,
 * {@code task} 的工具定义和分发逻辑直接内联在 {@link #buildTools} / {@link #executeOneTool}。
 *
 * <p><b>动机</b>:消除三方循环依赖
 * {@code TaskTool → Subagent → ToolRegistry → List<Tool> ⊃ TaskTool}。
 * 任何 @Lazy / ObjectProvider 方案都只是"让循环能被装配",不是"消除循环"。
 * 真正消除需要打破其中一条边 —— 把 task 从 List&lt;Tool&gt; 里拿出来,正是这种"结构性消除"。
 *
 * <p><b>取舍</b>:破坏了"加新 Tool 只需加 @Component"的统一性,但 {@code task} 本来就不是
 * 普通工具 —— 它是 agent runtime 自己派子 agent 的能力。把它和 BashTool / FileSystemTool
 * 这种"调用外部资源"的工具区别对待,反而更诚实。其它工具(bash / file / todo /
 * load_skill)继续走 @Component 自动收集,扩展开闭原则不变。
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

    /** task 工具名(内联工具,不出现在 List&lt;Tool&gt; 里 —— 见类注释循环依赖讨论)。*/
    private static final String TASK_TOOL_NAME = "task";

    /**
     * task 工具的 Anthropic 协议定义。内联在 AgentLoopHarness 而非 {@code @Component Tool},
     * 是为了消除 TaskTool↔Subagent↔ToolRegistry 三方循环 —— 详见类注释。
     */
    private static final ToolDefinition TASK_TOOL_DEFINITION = new ToolDefinition(
            TASK_TOOL_NAME,
            "Launch a subagent to handle a complex subtask. " +
                    "Use this when a sub-problem would clutter your own context " +
                    "(e.g. reading 100 files to find one thing). " +
                    "Returns only the final conclusion.",
            InputSchema.object(
                    Map.of("description", Map.of(
                            "type", "string",
                            "description", "The full task description to delegate")),
                    "description"
            )
    );

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
     * Subagent —— 由 {@link #execute(ToolCall)} 在 {@code task} 工具被调用时使用。
     * 直接注入(无 @Lazy):TaskTool 类已删除,Subagent 不再出现在 List&lt;Tool&gt; 里,
     * 三方循环已被 **结构性消除**(而非 @Lazy 打破)。
     */
    private final Subagent subagent;

    /**
     * SYSTEM prompt 运行期组装器(s10)。每轮 LLM 调用前由 {@link #agentLoop} 调用,
     * 反映当前 context(尤其是中途新写入的 memory)。
     */
    private final SystemPromptAssembler promptAssembler;

    /** 新会话回调列表。{@link #repl} 接到新 user 输入时会依次执行。 */
    private final List<Runnable> onNewSessionListeners = new ArrayList<>();

    /** REPL 多轮 history(跨会话清理由 onNewSession 注册)。 */
    private final List<MessageParam> history = new ArrayList<>();

    /**
     * 唯一构造器 —— Spring 容器装配。
     *
     * <p>{@code @Qualifier} 在 {@code json} 上让 Spring 解析到我们自己注册的
     * {@code marvisObjectMapper},而不是 Spring Boot 自带的 jackson auto-config 主 mapper。
     */
    public AgentLoopHarness(AnthropicClient client,
                            ToolRegistry registry,
                            @Qualifier("marvisObjectMapper") ObjectMapper json,
                            PermissionPipeline permissions,
                            HookManager hooks,
                            CompactPipeline compactPipeline,
                            MemoryService memoryService,
                            TodoStore todoStore,
                            Subagent subagent,
                            SystemPromptAssembler promptAssembler,
                            MarvisProperties props) {
        this.client = client;
        this.model = props.getAnthropic().getModel();
        this.registry = registry;
        this.json = json;
        this.permissions = permissions;
        this.hooks = hooks;
        this.compactPipeline = compactPipeline;
        this.memoryService = memoryService;
        this.todoStore = todoStore;
        this.subagent = subagent;
        this.promptAssembler = promptAssembler;
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

            CreateMessageRequest request = CreateMessageRequest.builder()
                    .model(model)
                    .system(system)
                    .messages(messages)
                    .tools(tools)
                    .maxTokens(MAX_TOKENS)
                    .build();

            CreateMessageResponse response;
            try {
                response = client.createMessage(request);
            } catch (AnthropicException e) {
                if (e.isPromptTooLong() && compactPipeline.hasReactiveSupport()) {
                    log.warn("[Loop] prompt_too_long detected, triggering L4 reactive compact");
                    boolean ok = compactPipeline.reactiveCompact(messages);
                    if (!ok) {
                        log.error("[Loop] L4 reactive compact failed, re-throwing");
                        throw e;
                    }
                    CreateMessageRequest retry = CreateMessageRequest.builder()
                            .model(model)
                            .system(system)
                            .messages(messages)
                            .tools(tools)
                            .maxTokens(MAX_TOKENS)
                            .build();
                    response = client.createMessage(retry);
                } else {
                    throw e;
                }
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

                ToolResultBlock result = executeOneTool(toolUse, args);
                hooks.triggerPostToolUse(toolUse, result.getContent().toString());

                if (TODO_TOOL_NAME.equals(toolUse.getName())) {
                    roundsSinceTodo = 0;
                }

                toolResults.add(result);
            }

            messages.add(MessageParam.toolResults(toolResults));
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
     * 把 {@link ToolRegistry} 里的工具转成 Anthropic 协议 ToolDef,**并追加 {@code task} 内联工具**。
     *
     * <p>{@code task} 不在 List&lt;Tool&gt; 里(为消除三方循环依赖,见类注释),
     * 在 LLM 看来它和其它工具没区别,只是分发逻辑写在 {@link #executeOneTool} 里。
     */
    private List<ToolDef> buildTools() {
        List<ToolDef> tools = new ArrayList<>();
        for (ToolDefinition def : registry.getAllTools()) {
            tools.add(new ToolDef(def.getName(), def.getDescription(), def.getInputSchema()));
        }
        // task 是 agent runtime 的内置工具,不出现在 List<Tool> 里,这里手动追加。
        tools.add(new ToolDef(
                TASK_TOOL_DEFINITION.getName(),
                TASK_TOOL_DEFINITION.getDescription(),
                TASK_TOOL_DEFINITION.getInputSchema()));
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
    }

    private void printToolHeader(ToolUseBlock toolUse, Map<String, Object> args) {
        Object cmd = args.get("command");
        String display = cmd != null ? cmd.toString() : args.toString();
        System.out.println("\033[33m$ " + display + "\033[0m");
    }

    /**
     * 派发一次工具调用。优先看是否是内置 {@code task} 工具,否则走普通 {@link ToolRegistry}。
     */
    private ToolResultBlock executeOneTool(ToolUseBlock toolUse, Map<String, Object> args) {
        ToolResult result = TASK_TOOL_NAME.equals(toolUse.getName())
                ? executeTaskTool(args)
                : registry.execute(new ToolCall(toolUse.getName(), args));
        String output = result.getOutput();

        System.out.println(output.length() > CONSOLE_PREVIEW_LIMIT
                ? output.substring(0, CONSOLE_PREVIEW_LIMIT) + "..."
                : output);

        return ToolResultBlock.ofText(toolUse.getId(), output);
    }

    /**
     * task 工具的内置实现:验参 → 调 {@link Subagent#spawn} → 包成 ToolResult。
     *
     * <p>原来这是 {@code TaskTool implements Tool},在 {@code @Component} 自动收集中。
     * 切片 C 之后为消除三方循环依赖删除了 TaskTool 类,逻辑内联到这里 ——
     * 这与 {@code task} 的本质相符:它不是普通工具,而是 agent runtime 自身派子 agent 的能力。
     */
    private ToolResult executeTaskTool(Map<String, Object> args) {
        Object descArg = args.get("description");
        if (descArg == null) {
            return new ToolResult(false, "Error: 'description' argument is required");
        }
        String description = descArg.toString();
        if (description.isBlank()) {
            return new ToolResult(false, "Error: 'description' must not be blank");
        }

        log.info("[Task] spawning subagent: {}",
                description.length() > 80 ? description.substring(0, 80) + "..." : description);

        try {
            return new ToolResult(true, subagent.spawn(description));
        } catch (Exception e) {
            log.error("[Task] subagent failed", e);
            return new ToolResult(false, "Subagent failed: " + e.getMessage());
        }
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

                fireOnNewSession();
                processOneQuery(query);

                printLastAssistantText(history);
                System.out.println();
            }
        }
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
