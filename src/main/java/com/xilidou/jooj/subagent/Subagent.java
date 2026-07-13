package com.xilidou.jooj.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojProperties;
import com.xilidou.jooj.agent.AgentInterruptedException;
import com.xilidou.jooj.agent.InterruptRegistry;
import com.xilidou.jooj.tool.ToolRegistry;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import com.xilidou.jooj.hook.HookManager;
import com.xilidou.jooj.http.AnthropicClient;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ToolDef;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
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
 * 由 {@link com.xilidou.jooj.tool.impl.TaskTool} 端用 {@code @Lazy} 打破 ——
 * Subagent 这边直接依赖 {@link ToolRegistry} 即可,无需任何 lazy 装配技巧。
 *
 * <p>测试不再 {@code new Subagent(...)},走 {@code @SpringBootTest + @Import(JoojTestConfig.class)}
 * 让 Spring 测试框架接管装配。
 *
 * <h3>简化设计</h3>
 *
 * <p>子 Agent 比父 Agent 简单:
 * <ul>
 *   <li>没有 nag 计数器、todo_write、UserPromptSubmit / Stop hook</li>
 *   <li>有 PreToolUse / PostToolUse hook —— **permission 必须作用于子 Agent**</li>
 *   <li>用独立 SYSTEM prompt({@link #SUB_SYSTEM_PROMPT})</li>
 *   <li>**显式白名单**(s12 Stage 3): 只暴露 {@link #DEFAULT_INCLUDED_TOOLS},严格对齐
 *       上游 s06 {@code SUB_TOOLS = [bash, read_file, write_file, edit_file, glob]}。
 *       这样默认安全 —— 加新工具(比如 s12 的 5 个 task 系列)默认不暴露给子 Agent,
 *       要明确加进白名单才行。</li>
 *   <li>max {@link #MAX_TURNS} 轮强制退出(防 infinite loop)</li>
 * </ul>
 */
@Slf4j
@Component
public class Subagent {

    /**
     * 子 Agent 可见的工具白名单 —— **跟上游 s06 {@code SUB_TOOLS} 严格一致**。
     *
     * <p>为什么不把整个 ToolRegistry 暴露给子 Agent:
     * <ul>
     *   <li>{@code task} 暴露给子 Agent 会导致递归 spawn,炸栈</li>
     *   <li>{@code todo_write} 暴露会污染父 Agent 的 TodoStore(子 Agent 写父的 todo 太离奇)</li>
     *   <li>s12 的 5 个 task 系列工具暴露给子 Agent 也不合适 —— task 状态管理是父 Agent
     *       的职责,子 Agent 只负责完成被派给的任务</li>
     *   <li>未来加任何工具(比如 git_commit / send_email)默认都应该不暴露给子 Agent,
     *       要明确审视过才能加进白名单 —— 白名单语义比黑名单安全得多</li>
     * </ul>
     *
     * <p><b>历史</b>:s12 Stage 3 之前是 {@code DEFAULT_EXCLUDED_TOOLS}(黑名单)。
     * 黑名单语义"不在名单里就放行"在加新工具时容易漏改;白名单"不在名单里就拒绝"是
     * fail-safe 默认。
     */
    public static final Set<String> DEFAULT_INCLUDED_TOOLS = Set.of(
            "bash", "read_file", "write_file", "edit_file", "glob");

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
    private final Set<String> includedTools;
    /**
     * s22 D-9:响应用户 interrupt。Subagent 是 lead 的"内部工具",lead 被打断时 subagent
     * 也应该停 —— 用 {@link InterruptRegistry#isRequested(String)}(**只读**,不消费)
     * 检查,让 flag 保留给 lead 消费一次(subagent 抛出后 → tool_result → lead 回到 while
     * 顶部再 consume 一次)。
     */
    private final InterruptRegistry interruptRegistry;

    /**
     * 唯一构造器 —— Spring 容器装配。
     *
     * <p>{@link ToolRegistry} 直接依赖,无 {@code @Lazy} —— 三方循环由
     * {@link com.xilidou.jooj.tool.impl.TaskTool} 端用 @Lazy 打破,
     * Subagent 这边不需要任何 lazy 装配技巧。
     */
    public Subagent(AnthropicClient client,
                    ToolRegistry registry,
                    @Qualifier("joojObjectMapper") ObjectMapper json,
                    HookManager hooks,
                    JoojProperties props,
                    InterruptRegistry interruptRegistry) {
        this.client = client;
        this.model = props.getAnthropic().getModel();
        this.registry = registry;
        this.json = json;
        this.hooks = hooks;
        this.includedTools = DEFAULT_INCLUDED_TOOLS;
        this.interruptRegistry = interruptRegistry;
    }

    /**
     * 派生一个子 Agent 跑给定任务,返回最终摘要文本。
     *
     * <p>s22 D-9:向后兼容入口 —— 不传 parentSessionId。用于测试路径 / 无 session 场景。
     * 生产路径应走 {@link #spawn(String, String)},让用户 interrupt 能穿透到 subagent 内部。
     */
    public String spawn(String description) {
        return spawn(description, null);
    }

    /**
     * s22 D-9:响应用户 interrupt 的 spawn 入口。
     *
     * <p>{@code parentSessionId} 是 lead loop 的 sessionId。Subagent 在 for turn 顶部
     * 和 tool 循环之间调 {@link InterruptRegistry#isRequested(String)}(**只读**)
     * 检查 flag,true 时抛 {@link AgentInterruptedException}。
     *
     * <p><b>为什么只读不消费</b>:flag 该由 lead 的 while 顶部消费一次 —— subagent 抛出后
     * TaskTool 会把它转成 {@code [Subagent interrupted]} tool_result 返给 lead;lead 拿到
     * tool_result 后回到 while 顶部,那次 {@code consumeIfRequested} 才真消费 + 抛出 →
     * 走 D-8 已建立的路径(append [Interrupted by user] + publish TurnInterrupted)。
     *
     * @param description       子任务描述
     * @param parentSessionId   lead loop 的 sessionId;{@code null} 时禁用 interrupt 检查(向后兼容)
     * @throws AgentInterruptedException 若 subagent 运行期间用户请求打断
     */
    public String spawn(String description, String parentSessionId) {
        if (description == null || description.isBlank()) {
            return "Subagent error: empty task description";
        }

        System.out.println();
        System.out.println(PURPLE + "[Subagent spawned]" + RESET);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user(description));    // fresh context

        List<ToolDef> tools = buildSubTools();

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            // s22 D-9:每轮 turn 顶部检查 interrupt(只读,不消费)
            checkInterrupt(parentSessionId);

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
                // s22 D-9:每个 tool 之间也检查 —— 用户点 stop 后已跑完的 tool 结果不进 messages,
                // subagent 直接抛出。跟 lead 的检查点粒度对齐。
                checkInterrupt(parentSessionId);

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

    /**
     * s22 D-9:interrupt 检查点辅助方法。
     *
     * <p>{@code parentSessionId=null} 时(测试/兼容路径)跳过检查。用
     * {@link InterruptRegistry#isRequested} 只读检查,让 flag 保留给 lead 消费。
     */
    private void checkInterrupt(String parentSessionId) {
        if (parentSessionId == null || interruptRegistry == null) return;
        if (interruptRegistry.isRequested(parentSessionId)) {
            log.info("[Subagent] interrupted by user request for parent sid={}", parentSessionId);
            throw new AgentInterruptedException(parentSessionId);
        }
    }

    private List<ToolDef> buildSubTools() {
        List<ToolDef> tools = new ArrayList<>();
        for (ToolDefinition def : registry.getAllTools()) {
            // **白名单过滤**(s12 Stage 3):只暴露 includedTools 里的工具,
            // 默认安全 —— 加新工具不进白名单就拿不到子 Agent 的访问权。
            if (!includedTools.contains(def.getName())) continue;
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
