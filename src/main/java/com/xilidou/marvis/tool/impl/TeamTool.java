package com.xilidou.marvis.tool.impl;

import com.xilidou.marvis.http.dto.InputSchema;
import com.xilidou.marvis.subagent.Teammate;
import com.xilidou.marvis.team.Message;
import com.xilidou.marvis.team.MessageBus;
import com.xilidou.marvis.tool.Tool;
import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.tool.ToolDefinition;
import com.xilidou.marvis.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Lead 端的 3 个 team 工具,严格对齐上游 s15 [s15_agent_teams/code.py]。
 *
 * <ol>
 *   <li>{@code spawn_teammate(name, role, prompt)} —— 派一个新队友 daemon thread</li>
 *   <li>{@code send_message(to, content)} —— Lead 主动发消息到某队友的 inbox</li>
 *   <li>{@code check_inbox()} —— 显式取一次 lead 的 inbox(常规 drain 在 processOneQuery 末尾自动跑,
 *       这个工具是给 LLM 主动 poll 的备用路径,跟上游一致)</li>
 * </ol>
 *
 * <p>跟 {@link CronTool} / {@link TasksTool} 同模式 —— 一个 {@link Tool} 暴露多个 ToolDefinition,
 * switch 派发。
 *
 * <p><b>Subagent 白名单不含本工具</b> —— 队友不能再 spawn 队友(防递归),
 * 跟上游 {@code teammates spawning other teammates} 显式禁止一致。
 */
@Component
@Slf4j
public class TeamTool implements Tool {

    private static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";

    /** Lead 自身的 agent name —— inbox 文件名。 */
    public static final String LEAD_NAME = "lead";

    private final Teammate teammate;
    private final MessageBus bus;

    /**
     * {@code @Lazy} 打破循环依赖,跟 {@link TaskTool} 同思路。
     *
     * <p>循环路径:
     * <pre>
     *   TeamTool ──(@Lazy)──▶ Teammate ─▶ ToolRegistry ─▶ List&lt;Tool&gt; ⊃ TeamTool
     * </pre>
     *
     * <p>没有 @Lazy 的话 Spring 实例化 ToolRegistry 时要先把 TeamTool 装好,
     * TeamTool 又要 Teammate,Teammate 又要 ToolRegistry — 死循环。
     * @Lazy 让 Spring 注入 Teammate 的代理,首次调用方法时才解析真身。
     */
    public TeamTool(@Lazy Teammate teammate, MessageBus bus) {
        this.teammate = teammate;
        this.bus = bus;
    }

    @Override
    public String getName() {
        return "team";
    }

    @Override
    public String getDescription() {
        return "Multi-agent team coordination: spawn_teammate / send_message / check_inbox.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        Map<String, Object> spawnSchema = Map.of(
                "name", Map.of("type", "string",
                        "description", "Unique teammate name (e.g. 'alice', 'researcher')"),
                "role", Map.of("type", "string",
                        "description", "Short role description (e.g. 'backend developer')"),
                "prompt", Map.of("type", "string",
                        "description", "Initial task prompt for the teammate")
        );
        Map<String, Object> sendSchema = Map.of(
                "to", Map.of("type", "string",
                        "description", "Recipient teammate name"),
                "content", Map.of("type", "string",
                        "description", "Message text")
        );
        return List.of(
                new ToolDefinition(
                        "spawn_teammate",
                        "Spawn a new teammate agent in a daemon thread. " +
                                "The teammate runs independently with its own messages and " +
                                "communicates via the MessageBus.",
                        InputSchema.object(spawnSchema, "name", "role", "prompt")),
                new ToolDefinition(
                        "send_message",
                        "Send a message to a teammate's inbox via the MessageBus.",
                        InputSchema.object(sendSchema, "to", "content")),
                new ToolDefinition(
                        "check_inbox",
                        "Read all messages currently in lead's inbox (consume-on-read).",
                        InputSchema.object(Map.of()))
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            return switch (call.getToolName()) {
                case "spawn_teammate" -> doSpawn(call);
                case "send_message" -> doSend(call);
                case "check_inbox" -> doCheckInbox();
                default -> new ToolResult(false, "Unknown tool: " + call.getToolName());
            };
        } catch (IllegalArgumentException e) {
            return new ToolResult(false, "Error: " + e.getMessage());
        } catch (Exception e) {
            log.error("[Team] tool {} failed", call.getToolName(), e);
            return new ToolResult(false, "Error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Handlers
    // ─────────────────────────────────────────────────────────────

    private ToolResult doSpawn(ToolCall call) {
        Object name = call.getArguments().get("name");
        Object role = call.getArguments().get("role");
        Object prompt = call.getArguments().get("prompt");
        if (name == null) return new ToolResult(false, "Error: 'name' is required");
        if (role == null) return new ToolResult(false, "Error: 'role' is required");
        if (prompt == null) return new ToolResult(false, "Error: 'prompt' is required");

        String result = teammate.spawn(name.toString(), role.toString(), prompt.toString());
        boolean success = result.startsWith("Spawned ");
        if (success) {
            System.out.println("  " + CYAN + "[team] " + result + RESET);
        }
        return new ToolResult(success, result);
    }

    private ToolResult doSend(ToolCall call) {
        Object to = call.getArguments().get("to");
        Object content = call.getArguments().get("content");
        if (to == null) return new ToolResult(false, "Error: 'to' is required");
        if (content == null) return new ToolResult(false, "Error: 'content' is required");

        bus.send(LEAD_NAME, to.toString(), content.toString(), "message");
        return new ToolResult(true, "Sent to " + to);
    }

    private ToolResult doCheckInbox() {
        List<Message> msgs = bus.readInbox(LEAD_NAME);
        if (msgs.isEmpty()) {
            return new ToolResult(true, "Inbox empty.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(msgs.size()).append(" message(s) in lead's inbox:\n");
        for (Message m : msgs) {
            sb.append("  ✉ from ").append(m.getFrom())
                    .append(" (").append(m.getType()).append("): ")
                    .append(m.getContent()).append("\n");
        }
        String out = sb.toString();
        if (out.endsWith("\n")) out = out.substring(0, out.length() - 1);
        return new ToolResult(true, out);
    }
}
