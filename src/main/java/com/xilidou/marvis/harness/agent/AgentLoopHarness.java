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
            "You are a coding agent at " + System.getProperty("user.dir") +
                    ". Use bash to solve tasks. Act, don't explain.";

    private static final int MAX_TOKENS = 8000;

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

    /**
     * 简化构造器：使用默认 ObjectMapper + 不做权限检查（所有工具直接执行）。
     * 用于测试场景或无权限需求的简单场景。
     *
     * @param client   LLM 客户端（生产用 {@link AnthropicHttpClient}，测试用 Mock）
     * @param model    模型 ID，如 {@code claude-sonnet-4-6}
     * @param registry 工具池
     */
    public AgentLoopHarness(AnthropicClient client, String model, SkillRegistry registry) {
        this(client, model, registry, JacksonConfig.newMapper(), PermissionPipeline.alwaysAllow());
    }

    /**
     * 完全自定义构造器。
     *
     * @param client      LLM 客户端
     * @param model       模型 ID
     * @param registry    工具池
     * @param json        Jackson ObjectMapper
     * @param permissions 权限 Pipeline（s03 三道闸门）
     */
    public AgentLoopHarness(AnthropicClient client, String model, SkillRegistry registry,
                            ObjectMapper json, PermissionPipeline permissions) {
        this.client = Objects.requireNonNull(client, "client");
        this.model = Objects.requireNonNull(model, "model");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.json = Objects.requireNonNull(json, "json");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
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
    public static AgentLoopHarness fromEnv() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        AnthropicClient client = AnthropicHttpClient.fromEnv(dotenv);
        String model = readEnv(dotenv, "MODEL_ID");

        SkillRegistry registry = new SkillRegistry();
        registry.load(new BashSkill());
        registry.load(new FileSystemSkill());

        return new AgentLoopHarness(
                client,
                model,
                registry,
                JacksonConfig.newMapper(),
                PermissionPipeline.defaultCli()    // ← s03：CLI 阻塞式审批
        );
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
     *   <li>执行所有 tool_use，收集结果</li>
     *   <li>把 tool_results 包在 user 消息里追加到 messages（坑 3：role 是 user）</li>
     * </ol>
     *
     * @param messages 对话历史，方法会原地修改
     */
    public void agentLoop(List<MessageParam> messages) {
        List<ToolDef> tools = buildTools();

        while (true) {
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

            // ③ stop_reason != tool_use → 退出
            if (!response.needsToolExecution()) {
                return;
            }

            // ④ 执行所有 tool_use
            List<ToolResultBlock> toolResults = executeToolUses(response.toolUses());

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

    /**
     * 执行一组 tool_use，返回对应的 tool_result。
     *
     * <p>每个 tool_use 都 try-catch 隔离：单个工具失败不会让整个 loop 崩溃，
     * 而是把错误以 {@code tool_result} 形式回传给 LLM，让它决定怎么处理。
     *
     * <p>s03 集成：在执行前过 {@link PermissionPipeline}，DENY 时不执行，
     * 把拒绝原因作为 tool_result 回传给 LLM（让模型知道为什么失败）。
     */
    private List<ToolResultBlock> executeToolUses(List<ToolUseBlock> toolUses) {
        List<ToolResultBlock> results = new ArrayList<>();
        for (ToolUseBlock toolUse : toolUses) {
            String toolName = toolUse.getName();
            Map<String, Object> args = parseToolInput(toolUse);

            // 屏幕显示（对应 Python 黄色输出）
            Object cmd = args.get("command");
            String display = cmd != null ? cmd.toString() : args.toString();
            System.out.println("\033[33m$ " + display + "\033[0m");

            // s03：权限检查
            PermissionResult permission = permissions.check(toolUse);
            if (permission.isDeny()) {
                String denyMsg = "Permission denied: " + permission.getReason();
                System.out.println("\033[31m⛔ " + denyMsg + "\033[0m");
                results.add(ToolResultBlock.ofText(toolUse.getId(), denyMsg));
                continue;
            }

            // 执行
            ToolResult result = registry.execute(new ToolCall(toolName, args));
            String output = result.getOutput();

            // 屏幕预览（200 字截断）
            System.out.println(output.length() > CONSOLE_PREVIEW_LIMIT
                    ? output.substring(0, CONSOLE_PREVIEW_LIMIT) + "..."
                    : output);

            results.add(ToolResultBlock.ofText(toolUse.getId(), output));
        }
        return results;
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
