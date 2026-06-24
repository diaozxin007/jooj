package com.xilidou.marvis.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.MarvisProperties;
import com.xilidou.marvis.tool.ToolRegistry;
import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.tool.ToolDefinition;
import com.xilidou.marvis.tool.ToolResult;
import com.xilidou.marvis.hook.HookManager;
import com.xilidou.marvis.http.AnthropicClient;
import com.xilidou.marvis.http.dto.CreateMessageRequest;
import com.xilidou.marvis.http.dto.CreateMessageResponse;
import com.xilidou.marvis.http.dto.MessageParam;
import com.xilidou.marvis.http.dto.TextBlock;
import com.xilidou.marvis.http.dto.ToolDef;
import com.xilidou.marvis.http.dto.ToolResultBlock;
import com.xilidou.marvis.http.dto.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Subagent - 用全新 messages[] 跑独立 loop,只返回最终摘要。
 *
 * <p>对应 Python s06 的 {@code spawn_subagent(description)}。
 *
 * <h3>核心思想:context 隔离</h3>
 *
 * <pre>
 *   Parent: messages=[full history]   →  task("分析 X 模块")
 *                                              ↓
 *   Subagent: messages=[task desc]   ← fresh context
 *             跑独立 loop(≤30 轮)
 *             返回 last assistant text only
 * </pre>
 *
 * <h3>切片 C 设计 — 单一构造器,无循环依赖</h3>
 *
 * <p>本类只暴露 <b>一个</b> 构造器,完全由 Spring 容器装配。
 *
 * <p>历史循环 {@code TaskTool → Subagent → ToolRegistry → List<Tool> ⊃ TaskTool}
 * 已被**结构性消除** —— {@code TaskTool} 类被删除,{@code task} 工具定义和
 * 分发逻辑直接内联到 {@link com.xilidou.marvis.agent.AgentLoopHarness}。
 * 现在 Subagent 直接依赖 {@link ToolRegistry},无 @Lazy / ObjectProvider 等
 * "让循环能被装配"的辅助手段,纯净的单向依赖。
 *
 * <p>测试不再 {@code new Subagent(...)},走 {@code @SpringBootTest + @Import(MarvisTestConfig.class)}
 * 让 Spring 测试框架接管装配。
 *
 * <h3>简化设计</h3>
 *
 * <p>子 Agent 比父 Agent 简单:
 * <ul>
 *   <li>没有 nag 计数器、todo_write、UserPromptSubmit / Stop hook</li>
 *   <li>有 PreToolUse / PostToolUse hook —— **permission 必须作用于子 Agent**</li>
 *   <li>用独立 SYSTEM prompt({@link #SUB_SYSTEM_PROMPT})</li>
 *   <li>过滤掉黑名单工具(默认排除 task 防递归 + todo_write 防污染父 store)</li>
 *   <li>max {@link #MAX_TURNS} 轮强制退出(防 infinite loop)</li>
 * </ul>
 */
@Slf4j
@Component
public class Subagent {

    /** 子 Agent 默认排除的工具:task(防递归)+ todo_write(防污染父 store)。 */
    public static final Set<String> DEFAULT_EXCLUDED_TOOLS = Set.of("task", "todo_write");

    /** 子 Agent 最大轮数(防 infinite loop)。 */
    public static final int MAX_TURNS = 30;

    private static final int MAX_TOKENS = 8000;

    private static final String SUB_SYSTEM_PROMPT =
            "You are a coding agent at " + System.getProperty("user.dir") + ". " +
                    "Complete the task you were given, then return a concise summary. " +
                    "Do not delegate further.";

    private static final String PURPLE = "\033[35m";
    private static final String GRAY = "\033[90m";
    private static final String RESET = "\033[0m";

    // ── 依赖(全部 final,Spring 构造器注入)──────────────────────
    private final AnthropicClient client;
    private final String model;
    private final ToolRegistry registry;
    private final ObjectMapper json;
    private final HookManager hooks;
    private final Set<String> excludedTools;

    /**
     * 唯一构造器 —— Spring 容器装配。
     *
     * <p>{@link ToolRegistry} 直接依赖,无 {@code @Lazy} —— 三方循环已被结构性消除
     * (TaskTool 类删除,task 工具内联到 AgentLoopHarness),不再需要 lazy 装配技巧。
     */
    public Subagent(AnthropicClient client,
                    ToolRegistry registry,
                    @Qualifier("marvisObjectMapper") ObjectMapper json,
                    HookManager hooks,
                    MarvisProperties props) {
        this.client = client;
        this.model = props.getAnthropic().getModel();
        this.registry = registry;
        this.json = json;
        this.hooks = hooks;
        this.excludedTools = DEFAULT_EXCLUDED_TOOLS;
    }

    /**
     * 派生一个子 Agent 跑给定任务,返回最终摘要文本。
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
                String result = extractText(messages);
                System.out.println(PURPLE + "[Subagent done]" + RESET);
                return result;
            }

            List<ToolResultBlock> toolResults = new ArrayList<>();
            for (ToolUseBlock toolUse : response.toolUses()) {
                Map<String, Object> args = parseToolInput(toolUse);

                Optional<String> blocked = hooks.triggerPreToolUse(toolUse);
                if (blocked.isPresent()) {
                    System.out.println(GRAY + "  [sub] ⛔ " + blocked.get() + RESET);
                    toolResults.add(ToolResultBlock.ofText(toolUse.getId(), blocked.get()));
                    continue;
                }

                ToolResult execResult = registry.execute(new ToolCall(toolUse.getName(), args));
                String output = execResult.getOutput();

                String preview = output.length() > 100 ? output.substring(0, 100) + "..." : output;
                System.out.println(GRAY + "  [sub] " + toolUse.getName() + ": " + preview + RESET);

                hooks.triggerPostToolUse(toolUse, output);

                toolResults.add(ToolResultBlock.ofText(toolUse.getId(), output));
            }

            messages.add(MessageParam.toolResults(toolResults));
        }

        log.warn("[Subagent] hit MAX_TURNS={} without natural stop", MAX_TURNS);
        String result = extractText(messages);
        if (result.isEmpty()) {
            result = "Subagent stopped after " + MAX_TURNS + " turns without final answer.";
        }
        System.out.println(PURPLE + "[Subagent done — max turns]" + RESET);
        return result;
    }

    private List<ToolDef> buildSubTools() {
        List<ToolDef> tools = new ArrayList<>();
        for (ToolDefinition def : registry.getAllTools()) {
            if (excludedTools.contains(def.getName())) continue;
            tools.add(new ToolDef(def.getName(), def.getDescription(), def.getInputSchema()));
        }
        return tools;
    }

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
