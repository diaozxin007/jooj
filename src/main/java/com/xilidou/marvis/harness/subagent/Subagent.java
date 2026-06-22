package com.xilidou.marvis.harness.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.harness.base.ToolRegistry;
import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.hook.HookManager;
import com.xilidou.marvis.harness.http.AnthropicClient;
import com.xilidou.marvis.harness.http.dto.CreateMessageRequest;
import com.xilidou.marvis.harness.http.dto.CreateMessageResponse;
import com.xilidou.marvis.harness.http.dto.MessageParam;
import com.xilidou.marvis.harness.http.dto.TextBlock;
import com.xilidou.marvis.harness.http.dto.ToolDef;
import com.xilidou.marvis.harness.http.dto.ToolResultBlock;
import com.xilidou.marvis.harness.http.dto.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Subagent - 用全新 messages[] 跑独立 loop，只返回最终摘要。
 *
 * <p>对应 Python s06 的 {@code spawn_subagent(description)}。
 *
 * <h3>核心思想：context 隔离</h3>
 *
 * <pre>
 *   Parent: messages=[full history]   →  task("分析 X 模块")
 *                                              ↓
 *   Subagent: messages=[task desc]   ← fresh context
 *             跑独立 loop（≤30 轮）
 *             返回 last assistant text only
 *                                              ↓
 *   Parent: 收到一段 summary 字符串，工具/思考过程全部丢弃
 * </pre>
 *
 * <p>这是**外置记忆原理的反面**：父 Agent 故意不知道子 Agent 怎么做，只要结果。
 * 用途：
 * <ul>
 *   <li>父 Agent 不想被子任务的中间细节污染 context（"读 100 个文件找 X" 之类）</li>
 *   <li>并行处理（Week 9 多 Agent 雏形）</li>
 *   <li>Specialist：子 Agent 用不同 SYSTEM prompt 处理特定领域</li>
 * </ul>
 *
 * <h3>简化设计</h3>
 *
 * <p>子 Agent 比父 Agent 简单：
 * <ul>
 *   <li>没有 nag 计数器、todo_write、UserPromptSubmit / Stop hook</li>
 *   <li>有 PreToolUse / PostToolUse hook —— **permission 必须作用于子 Agent**，
 *       否则父 Agent 派子 Agent 干危险事就成了漏洞</li>
 *   <li>用独立 SYSTEM prompt（{@link #SUB_SYSTEM_PROMPT}）</li>
 *   <li>过滤掉黑名单工具（默认排除 task 防递归 + todo_write 防污染父 store）</li>
 *   <li>max {@link #MAX_TURNS} 轮强制退出（防 infinite loop）</li>
 * </ul>
 */
@Slf4j
public class Subagent {

    /** 子 Agent 默认排除的工具：task（防递归）+ todo_write（防污染父 store）*/
    public static final Set<String> DEFAULT_EXCLUDED_TOOLS = Set.of("task", "todo_write");

    /** 子 Agent 最大轮数（防 infinite loop）*/
    public static final int MAX_TURNS = 30;

    private static final int MAX_TOKENS = 8000;

    private static final String SUB_SYSTEM_PROMPT =
            "You are a coding agent at " + System.getProperty("user.dir") + ". " +
                    "Complete the task you were given, then return a concise summary. " +
                    "Do not delegate further.";

    // 紫色，区别于父 Agent 的黄色 $
    private static final String PURPLE = "\033[35m";
    private static final String GRAY = "\033[90m";
    private static final String RESET = "\033[0m";

    // ── 依赖 ────────────────────────────────────────────────────
    private final AnthropicClient client;
    private final String model;
    private final ToolRegistry registry;
    private final ObjectMapper json;
    private final HookManager hooks;
    private final Set<String> excludedTools;

    /**
     * 默认构造器：排除 task / todo_write。
     */
    public Subagent(AnthropicClient client, String model, ToolRegistry registry,
                    ObjectMapper json, HookManager hooks) {
        this(client, model, registry, json, hooks, DEFAULT_EXCLUDED_TOOLS);
    }

    /**
     * 完全自定义：可指定哪些工具子 Agent 不能用。
     */
    public Subagent(AnthropicClient client, String model, ToolRegistry registry,
                    ObjectMapper json, HookManager hooks, Set<String> excludedTools) {
        this.client = Objects.requireNonNull(client, "client");
        this.model = Objects.requireNonNull(model, "model");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.json = Objects.requireNonNull(json, "json");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.excludedTools = Set.copyOf(excludedTools);
    }

    /**
     * 派生一个子 Agent 跑给定任务，返回最终摘要文本。
     *
     * @param description 任务描述（成为子 Agent 的第一条 user message）
     * @return 子 Agent 最后一条 assistant 消息的 text（中间过程丢弃）
     */
    public String spawn(String description) {
        if (description == null || description.isBlank()) {
            return "Subagent error: empty task description";
        }

        System.out.println();
        System.out.println(PURPLE + "[Subagent spawned]" + RESET);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user(description));    // fresh context

        List<ToolDef> tools = buildSubTools();

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            CreateMessageRequest request = CreateMessageRequest.builder()
                    .model(model)
                    .system(SUB_SYSTEM_PROMPT)
                    .messages(messages)
                    .tools(tools)
                    .maxTokens(MAX_TOKENS)
                    .build();

            CreateMessageResponse response = client.createMessage(request);
            messages.add(MessageParam.assistant(response.getContent()));

            if (!response.needsToolExecution()) {
                // 自然停止
                String result = extractText(messages);
                System.out.println(PURPLE + "[Subagent done]" + RESET);
                return result;
            }

            // 执行工具
            List<ToolResultBlock> toolResults = new ArrayList<>();
            for (ToolUseBlock toolUse : response.toolUses()) {
                Map<String, Object> args = parseToolInput(toolUse);

                // PreToolUse hook（permission 也作用于 subagent，关键）
                Optional<String> blocked = hooks.triggerPreToolUse(toolUse);
                if (blocked.isPresent()) {
                    System.out.println(GRAY + "  [sub] ⛔ " + blocked.get() + RESET);
                    toolResults.add(ToolResultBlock.ofText(toolUse.getId(), blocked.get()));
                    continue;
                }

                ToolResult execResult = registry.execute(new ToolCall(toolUse.getName(), args));
                String output = execResult.getOutput();

                // 屏幕预览（前 100 字，灰色，区别于父 Agent 的截断）
                String preview = output.length() > 100 ? output.substring(0, 100) + "..." : output;
                System.out.println(GRAY + "  [sub] " + toolUse.getName() + ": " + preview + RESET);

                // PostToolUse hook
                hooks.triggerPostToolUse(toolUse, output);

                toolResults.add(ToolResultBlock.ofText(toolUse.getId(), output));
            }

            messages.add(MessageParam.toolResults(toolResults));
        }

        // 跑满 30 轮还没自然停 — fallback 取最后一条 assistant text
        log.warn("[Subagent] hit MAX_TURNS={} without natural stop", MAX_TURNS);
        String result = extractText(messages);
        if (result.isEmpty()) {
            result = "Subagent stopped after " + MAX_TURNS + " turns without final answer.";
        }
        System.out.println(PURPLE + "[Subagent done — max turns]" + RESET);
        return result;
    }

    /**
     * 把 ToolRegistry 里的工具转成 ToolDef 列表，过滤掉黑名单。
     *
     * <p>这与 {@link com.xilidou.marvis.harness.agent.AgentLoopHarness#buildTools} 几乎一样，
     * 唯一区别是过滤——这个小重复是值得的（避免 Agent / Subagent 互相引用代码）。
     */
    private List<ToolDef> buildSubTools() {
        List<ToolDef> tools = new ArrayList<>();
        for (ToolDefinition def : registry.getAllTools()) {
            if (excludedTools.contains(def.getName())) continue;
            tools.add(new ToolDef(def.getName(), def.getDescription(), def.getInputSchema()));
        }
        return tools;
    }

    /**
     * 从消息列表的最后一条 assistant message 提取文本。
     *
     * <p>如果最后是 tool_result（user 消息），向上回找最近一条 assistant text。
     */
    private String extractText(List<MessageParam> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageParam msg = messages.get(i);
            if (!"assistant".equals(msg.getRole())) continue;

            Object content = msg.getContent();
            if (content instanceof List<?> blocks) {
                StringBuilder sb = new StringBuilder();
                for (Object block : blocks) {
                    if (block instanceof TextBlock t && t.getText() != null) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(t.getText());
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }
        }
        return "";
    }

    private Map<String, Object> parseToolInput(ToolUseBlock toolUse) {
        try {
            Map<String, Object> converted = json.convertValue(toolUse.getInput(),
                    new TypeReference<>() {});
            return converted != null ? converted : new HashMap<>();
        } catch (Exception e) {
            log.error("[Subagent] Failed to parse tool input for {}: {}",
                    toolUse.getName(), e.getMessage());
            return new HashMap<>();
        }
    }
}
