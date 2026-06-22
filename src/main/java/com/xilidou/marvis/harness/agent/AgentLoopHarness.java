package com.xilidou.marvis.harness.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.harness.JacksonConfig;
import com.xilidou.marvis.harness.base.SkillRegistry;
import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.http.AnthropicClient;
import com.xilidou.marvis.harness.http.AnthropicHttpClient;
import com.xilidou.marvis.harness.http.dto.ContentBlock;
import com.xilidou.marvis.harness.http.dto.CreateMessageRequest;
import com.xilidou.marvis.harness.http.dto.CreateMessageResponse;
import com.xilidou.marvis.harness.http.dto.InputSchema;
import com.xilidou.marvis.harness.http.dto.MessageParam;
import com.xilidou.marvis.harness.http.dto.TextBlock;
import com.xilidou.marvis.harness.http.dto.ToolDef;
import com.xilidou.marvis.harness.http.dto.ToolResultBlock;
import com.xilidou.marvis.harness.http.dto.ToolUseBlock;
import com.xilidou.marvis.harness.hook.HookManager;
import com.xilidou.marvis.harness.permission.PermissionPipeline;
import com.xilidou.marvis.harness.permission.PermissionResult;
import com.xilidou.marvis.harness.skill.impl.BashSkill;
import com.xilidou.marvis.harness.skill.impl.FileSystemSkill;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;

/**
 * AgentLoopHarness - s01_agent_loop.py 的 Java 实现。
 *
 * <p>核心模式（loop 永远不变，是 agent 的核心）：
 * <pre>
 *   while stop_reason == "tool_use":
 *       response = LLM(messages, tools)
 *       execute tools
 *       append results
 * </pre>
 *
 * <p>持续将工具结果反馈给模型，直到模型决定停止。
 *
 * <p>设计要点（v2 - OkHttp 直连版）：
 * <ul>
 *   <li><b>构造器注入</b>：所有依赖通过构造函数传入，方便测试和替换 LLM 实现</li>
 *   <li><b>{@link #fromEnv}</b>：开箱即用工厂，从 .env 装配（CLI 场景）</li>
 *   <li><b>无 SDK 依赖</b>：完全通过 {@link AnthropicClient} 接口与 LLM 交互</li>
 * </ul>
 *
 * <p>典型用法：
 * <pre>
 *   // 路径 1：开箱即用
 *   AgentLoopHarness.fromEnv().repl();
 *
 *   // 路径 2：测试场景（注入 Mock）
 *   AnthropicClient mock = new MockAnthropicClient(...);
 *   AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry);
 *   harness.agentLoop(messages);
 * </pre>
 */
@Slf4j
public class AgentLoopHarness {

    private static final String SYSTEM_PROMPT =
            "You are a coding agent at " + System.getProperty("user.dir") + ". " +
                    "Before starting any multi-step task, use todo_write to plan your steps. " +
                    "Update task status as you go. " +
                    "Use bash, read_file, write_file, edit_file, glob to execute. " +
                    "Act, don't explain.";

    private static final int MAX_TOKENS = 8000;

    /**
     * Nag 阈值：连续多少轮 LLM 调用没有 todo_write，就注入 reminder。
     *
     * <p>对应 Python s05 的 {@code rounds_since_todo >= 3}。
     */
    private static final int NAG_THRESHOLD = 3;

    /** todo 工具名（注入 reminder 时识别用）*/
    private static final String TODO_TOOL_NAME = "todo_write";

    /**
     * 工具结果输出在屏幕上的截断长度（200 chars）。
     * 注意：这只影响打印，模型收到的是完整 50000 chars（由 BashSkill 控制）。
     */
    private static final int CONSOLE_PREVIEW_LIMIT = 200;

    // ── 依赖（构造器注入）──────────────────────────────────────
    private final AnthropicClient client;
    private final String model;
    private final SkillRegistry registry;
    private final ObjectMapper json;
    private final PermissionPipeline permissions;
    private final HookManager hooks;

    /**
     * 新会话回调列表。{@link #repl} 接到新 user 输入时会依次执行。
     *
     * <p>典型用途：清空 {@link com.xilidou.marvis.harness.todo.TodoStore}（避免上一次任务的
     * todo 串到下一次）、重置 {@link com.xilidou.marvis.harness.hook.impl.MetricsHook} metric 等。
     *
     * <p>用 {@code List<Runnable>} 而非具体类型，是为了让 Loop 不耦合具体业务——
     * 谁需要"新会话清理"自己注册回调即可。
     */
    private final List<Runnable> onNewSessionListeners = new ArrayList<>();

    /**
     * 简化构造器：使用默认 ObjectMapper + 不做权限检查（所有工具直接执行）+ 空 hooks。
     * 用于测试场景或无权限需求的简单场景。
     *
     * @param client   LLM 客户端（生产用 {@link AnthropicHttpClient}，测试用 Mock）
     * @param model    模型 ID，如 {@code claude-sonnet-4-6}
     * @param registry 工具池
     */
    public AgentLoopHarness(AnthropicClient client, String model, SkillRegistry registry) {
        this(client, model, registry,
                JacksonConfig.newMapper(),
                PermissionPipeline.alwaysAllow(),
                new HookManager());
    }

    /**
     * 5 参构造器（测试场景常用：注入特定 PermissionPipeline）。
     * hooks 默认空，permission 走旧路径（直接调 pipeline）。
     */
    public AgentLoopHarness(AnthropicClient client, String model, SkillRegistry registry,
                            ObjectMapper json, PermissionPipeline permissions) {
        this(client, model, registry, json, permissions, new HookManager());
    }

    /**
     * 完全自定义构造器（s04 完整版）。
     *
     * @param client      LLM 客户端
     * @param model       模型 ID
     * @param registry    工具池
     * @param json        Jackson ObjectMapper
     * @param permissions 权限 Pipeline（s03 三道闸门，作为 fallback）
     * @param hooks       Hook 管理器（s04，PreToolUse 等事件分发）
     */
    public AgentLoopHarness(AnthropicClient client, String model, SkillRegistry registry,
                            ObjectMapper json, PermissionPipeline permissions, HookManager hooks) {
        this.client = Objects.requireNonNull(client, "client");
        this.model = Objects.requireNonNull(model, "model");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.json = Objects.requireNonNull(json, "json");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    // ── 工厂方法 ────────────────────────────────────────────────

    /**
     * 从 .env 装配，注册默认 Skills（BashSkill + FileSystemSkill）。CLI 场景用。
     *
     * <p>等价于：
     * <pre>
     *   Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
     *   AnthropicClient client = AnthropicHttpClient.fromEnv(dotenv);
     *   String model = dotenv.get("MODEL_ID");
     *   SkillRegistry registry = new SkillRegistry();
     *   registry.load(new BashSkill());
     *   registry.load(new FileSystemSkill());
     *   return new AgentLoopHarness(client, model, registry);
     * </pre>
     */
    /**
     * 从 .env 装配，注册默认 Skills（BashSkill + FileSystemSkill）。CLI 场景用。
     *
     * <p>等价于：
     * <pre>
     *   Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
     *   AnthropicClient client = AnthropicHttpClient.fromEnv(dotenv);
     *   String model = dotenv.get("MODEL_ID");
     *   SkillRegistry registry = new SkillRegistry(List.of(new BashSkill(), new FileSystemSkill()));
     *   return new AgentLoopHarness(client, model, registry, ...);
     * </pre>
     *
     * <p>⚠️ 这个工厂仅供 raw main 入口（如 SmokeTest）使用。
     * 主入口 {@link com.xilidou.marvis.S01} 已迁移到 Spring CommandLineRunner，
     * Skill 注册由 Spring 自动完成（通过 {@code @Component}）。
     */
    public static AgentLoopHarness fromEnv() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        AnthropicClient client = AnthropicHttpClient.fromEnv(dotenv);
        String model = readEnv(dotenv, "MODEL_ID");

        // 非 Spring 场景：手工实例化 Skill。Spring 场景用 @Component 自动注入。
        com.xilidou.marvis.harness.todo.TodoStore todoStore =
                new com.xilidou.marvis.harness.todo.TodoStore();

        // s04 hook：手工注册 PermissionHook + ToolUseLogHook + LargeOutputHook + MetricsHook
        // permissions Pipeline 给 PermissionHook 用，作为 fallback 也保留在 Loop（虽然不再被直接调用）
        PermissionPipeline permissions = PermissionPipeline.defaultCli();
        com.xilidou.marvis.harness.hook.impl.MetricsHook metrics =
                new com.xilidou.marvis.harness.hook.impl.MetricsHook();
        HookManager hooks = new HookManager()
                .register(new com.xilidou.marvis.harness.hook.impl.PermissionHook(permissions))
                .register(new com.xilidou.marvis.harness.hook.impl.ToolUseLogHook())
                // metrics 同时实现 OnPreToolUse 和 OnPostToolUse，要分别注册（手工场景）
                .register((com.xilidou.marvis.harness.hook.Hook.OnPreToolUse) metrics)
                .register((com.xilidou.marvis.harness.hook.Hook.OnPostToolUse) metrics)
                .register(new com.xilidou.marvis.harness.hook.impl.LargeOutputHook());

        // s06: 子 Agent 共享 hooks（permission 也作用于子 Agent，关键安全保证）
        // 子 Agent 用同一个 SkillRegistry 但通过 excludedTools 过滤掉 task / todo_write
        // 注意：SkillRegistry 此时还在初始化，TaskSkill 需要 Subagent，Subagent 需要
        // SkillRegistry —— 用 builder pattern 切断循环：先建 registry（不含 task），
        // 建 Subagent 持有 registry 引用，再把 TaskSkill 加到 registry
        com.xilidou.marvis.harness.skill.impl.TodoSkill todoSkill =
                new com.xilidou.marvis.harness.skill.impl.TodoSkill(todoStore);
        SkillRegistry registry = new SkillRegistry(List.of(
                new BashSkill(),
                new FileSystemSkill(),
                todoSkill
        ));

        com.xilidou.marvis.harness.subagent.Subagent subagent =
                new com.xilidou.marvis.harness.subagent.Subagent(
                        client, model, registry, JacksonConfig.newMapper(), hooks);
        registry.load(new com.xilidou.marvis.harness.skill.impl.TaskSkill(subagent));

        return new AgentLoopHarness(
                client,
                model,
                registry,
                JacksonConfig.newMapper(),
                permissions,
                hooks
        ).onNewSession(todoStore::clear);   // s05 修复：新 query 清空 todo
    }

    private static String readEnv(Dotenv dotenv, String key) {
        String v = dotenv.get(key);
        if (v == null || v.isBlank()) v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing env: " + key);
        }
        return v;
    }

    // ── 核心 Agent Loop ─────────────────────────────────────────

    /**
     * 对应 Python s01 的 agent_loop(messages)。
     *
     * <p>循环直到 LLM 不再 tool_use（end_turn / max_tokens / stop_sequence）。
     *
     * <p>每轮都会：
     * <ol>
     *   <li>调 LLM</li>
     *   <li>把 assistant 回复**完整**追加到 messages（坑 4：必须原样回传）</li>
     *   <li>如果 stop_reason != tool_use，退出</li>
     *   <li>对每个 tool_use：屏幕显示 → 权限检查 → 执行 → 收集结果</li>
     *   <li>把 tool_results 包在 user 消息里追加到 messages（坑 3：role 是 user）</li>
     * </ol>
     *
     * <p>设计原则：工具调用的完整生命周期（display → permission → exec）
     * 全部展开在 loop 层，**单一职责的步骤一目了然**。这也对应 Python s03 的写法
     * （没有 executeToolUses 这种封装，所有逻辑都在 for 循环里）。
     *
     * <p>s04 切到 Hooks 时只需替换 {@code permissions.check} 那一行为
     * {@code hooks.trigger(PRE_TOOL_USE)}，其他位置不变。
     *
     * @param messages 对话历史，方法会原地修改
     */
    public void agentLoop(List<MessageParam> messages) {
        List<ToolDef> tools = buildTools();

        // s05: nag 计数器——连续 NAG_THRESHOLD 轮没调 todo_write 就注入提醒
        // 这是 Loop-级别状态，方法调用结束（一次完整 user 输入处理完）就重置
        // 多轮 user-assistant 对话间的连续性由调用方（repl）维护
        int roundsSinceTodo = 0;

        while (true) {
            // s05 nag: 在调 LLM 前注入 reminder（让模型在思考下一步时看到提醒）
            if (roundsSinceTodo >= NAG_THRESHOLD && !messages.isEmpty()) {
                messages.add(MessageParam.user(
                        "<reminder>You haven't updated your todos for " + NAG_THRESHOLD +
                                " rounds. Use todo_write to update task statuses.</reminder>"));
                log.info("[Loop] nag reminder injected after {} rounds without todo_write", roundsSinceTodo);
                roundsSinceTodo = 0;
            }

            // ① 调 LLM
            CreateMessageRequest request = CreateMessageRequest.builder()
                    .model(model)
                    .system(SYSTEM_PROMPT)
                    .messages(messages)
                    .tools(tools)
                    .maxTokens(MAX_TOKENS)
                    .build();

            CreateMessageResponse response = client.createMessage(request);

            // ② 把 assistant 回复**完整**追加（坑 4）
            messages.add(MessageParam.assistant(response.getContent()));

            // ③ stop_reason != tool_use → 触发 Stop hook → 退出（或强制再来一轮）
            if (!response.needsToolExecution()) {
                Optional<String> forceContinue = hooks.triggerStop(messages);
                if (forceContinue.isPresent()) {
                    // Stop hook 返回非空 = "loop 再来一轮"语义
                    // 把 hook 给的内容作为 user 消息追加，模型会基于此继续思考
                    messages.add(MessageParam.user(forceContinue.get()));
                    continue;
                }
                return;
            }

            // 这一轮有 tool_use → nag 计数 +1（todo_write 调用后会清零）
            roundsSinceTodo++;

            // ④ 对每个 tool_use：display → PreToolUse hook → exec → PostToolUse hook → collect
            List<ToolResultBlock> toolResults = new ArrayList<>();
            for (ToolUseBlock toolUse : response.toolUses()) {
                Map<String, Object> args = parseToolInput(toolUse);

                // 4a. 屏幕显示（对应 Python 黄色输出）
                printToolHeader(toolUse, args);

                // 4b. PreToolUse hook（s04 替代 s03 的 permissions.check）
                //     PermissionHook 会跑 permission pipeline；其他 hook（log/metric）也在这里
                //     第一个返回非空 Optional 的 hook 短路，其值作为 deny 原因
                Optional<String> blocked = hooks.triggerPreToolUse(toolUse);
                if (blocked.isPresent()) {
                    System.out.println("\033[31m⛔ " + blocked.get() + "\033[0m");
                    toolResults.add(ToolResultBlock.ofText(toolUse.getId(), blocked.get()));
                    continue;
                }

                // 4c. 纯执行（不关心权限和 hook）
                ToolResultBlock result = executeOneTool(toolUse, args);

                // 4d. PostToolUse hook（log 大输出、metric、缓存等）
                //     当前 hook 都是 observability 性质，不阻止；将来可以扩展（截断输出）
                hooks.triggerPostToolUse(toolUse, result.getContent().toString());

                // s05: 调了 todo_write 就重置 nag 计数
                if (TODO_TOOL_NAME.equals(toolUse.getName())) {
                    roundsSinceTodo = 0;
                }

                toolResults.add(result);
            }

            // ⑤ 把 tool_results 包成 user 消息（坑 3）
            messages.add(MessageParam.toolResults(toolResults));
        }
    }

    /**
     * 把 SkillRegistry 里的工具转成 Anthropic 协议的 ToolDef 列表。
     *
     * <p>这是 SkillRegistry（内部抽象）和 Anthropic 协议（外部协议）之间的适配层。
     * 因为 {@link ToolDefinition} 已经持有结构化的 {@link InputSchema}，
     * 适配是 1:1 的字段拷贝。将来切 OpenAI 时，只需改这个方法的目标类型。
     */
    private List<ToolDef> buildTools() {
        List<ToolDef> tools = new ArrayList<>();
        for (ToolDefinition def : registry.getAllTools()) {
            tools.add(new ToolDef(def.getName(), def.getDescription(), def.getInputSchema()));
        }
        return tools;
    }

    // ── 新会话生命周期 ──────────────────────────────────────────

    /**
     * 注册"新会话开始时"要执行的回调。
     *
     * <p>用法（从 {@link #fromEnv} 看）：
     * <pre>
     *   harness.onNewSession(todoStore::clear);    // 清空 todo
     *   harness.onNewSession(metricsHook::reset);  // 重置 metric（如需）
     * </pre>
     *
     * <p>**为什么需要这个机制**：LLM 看到 messages 历史里有上一轮的 todo_write 记录时，
     * 容易把"已完成的旧 todo"和"新任务的 todo"混合输出。在新 user query 时显式清空
     * TodoStore 让系统状态与"新会话"语义对齐。
     *
     * @return this，支持链式调用
     */
    public AgentLoopHarness onNewSession(Runnable callback) {
        if (callback != null) {
            onNewSessionListeners.add(callback);
        }
        return this;
    }

    /**
     * 触发所有"新会话"回调。{@link #repl} 在每次接到新 user 输入后调用。
     *
     * <p>单个回调抛异常**不应**让其他回调失败——所有回调都尽量执行。
     */
    private void fireOnNewSession() {
        for (Runnable callback : onNewSessionListeners) {
            try {
                callback.run();
            } catch (Exception e) {
                log.warn("[Loop] onNewSession callback failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 在屏幕上打印一行黄色的工具调用头（对应 Python 的 {@code $ command} 风格）。
     *
     * <p>纯 UI 输出，不影响业务逻辑。
     */
    private void printToolHeader(ToolUseBlock toolUse, Map<String, Object> args) {
        Object cmd = args.get("command");
        String display = cmd != null ? cmd.toString() : args.toString();
        System.out.println("\033[33m$ " + display + "\033[0m");
    }

    /**
     * 纯执行一个工具调用——不做权限检查，不做屏幕显示装饰。
     *
     * <p>职责：
     * <ol>
     *   <li>派发到对应 Skill 的 execute</li>
     *   <li>把输出截断后打印到屏幕（200 字预览）</li>
     *   <li>包装成 {@link ToolResultBlock} 返回</li>
     * </ol>
     *
     * <p>权限和 UI header 由调用方（{@link #agentLoop}）负责，本方法只关心执行。
     */
    private ToolResultBlock executeOneTool(ToolUseBlock toolUse, Map<String, Object> args) {
        ToolResult result = registry.execute(new ToolCall(toolUse.getName(), args));
        String output = result.getOutput();

        // 屏幕预览（200 字截断）
        System.out.println(output.length() > CONSOLE_PREVIEW_LIMIT
                ? output.substring(0, CONSOLE_PREVIEW_LIMIT) + "..."
                : output);

        return ToolResultBlock.ofText(toolUse.getId(), output);
    }

    /**
     * 把 tool_use.input（JsonNode）转成 Map。
     *
     * <p>JsonNode → Map 的转换有概率失败（如果 input 不是 object 而是其他类型），
     * 失败时返回空 Map 让工具自己判断错误，不让整个 loop 挂掉。
     */
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
     * 启动交互式 REPL（对应 s01 main 的 while 循环）。
     */
    public void repl() {
        System.out.println("s01: Agent Loop (Java)");
        System.out.println("输入问题，回车发送。输入 q 退出。\n");

        List<MessageParam> history = new ArrayList<>();

        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            while (true) {
                System.out.print("\033[36ms01 >> \033[0m");
                if (!scanner.hasNextLine()) break;

                String query = scanner.nextLine().strip();
                if (query.equalsIgnoreCase("q")
                        || query.equalsIgnoreCase("exit")
                        || query.isEmpty()) break;

                // s04: UserPromptSubmit hook（在发给 LLM 之前）
                // 当前 hook 都是 observability 性质（log）；
                // 将来可扩展：返回非空 Optional 阻止 query 进入 loop（敏感词过滤等）
                Optional<String> blocked = hooks.triggerUserPrompt(query);
                if (blocked.isPresent()) {
                    System.out.println("\033[31m⛔ Prompt blocked: " + blocked.get() + "\033[0m");
                    continue;
                }

                // s05+: 触发"新会话开始"回调。
                // 典型用途：清空 TodoStore 避免上一次任务的 todo 串到下一次。
                // 见 fromEnv() 里 todoStore::clear 的注册。
                fireOnNewSession();

                history.add(MessageParam.user(query));

                agentLoop(history);

                // 打印模型最终文本回复（loop 结束时最后一条 assistant message）
                printLastAssistantText(history);
                System.out.println();
            }
        }
    }

    /**
     * 从 history 末尾找最后一条 assistant message，打印其中的 text blocks。
     */
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

    // ── main 入口（保持 Backward 兼容，原 main 行为不变）────────
    public static void main(String[] args) {
        AgentLoopHarness.fromEnv().repl();
    }
}
