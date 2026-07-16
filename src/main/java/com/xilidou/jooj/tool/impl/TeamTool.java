package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.subagent.Teammate;
import com.xilidou.jooj.team.Message;
import com.xilidou.jooj.team.MessageBus;
import com.xilidou.jooj.team.ProtocolRegistry;
import com.xilidou.jooj.team.ProtocolState;
import com.xilidou.jooj.tool.Tool;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
    private final ProtocolRegistry protocols;

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
    public TeamTool(@Lazy Teammate teammate, MessageBus bus, ProtocolRegistry protocols) {
        this.teammate = teammate;
        this.bus = bus;
        this.protocols = protocols;
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
        Map<String, Object> requestShutdownSchema = Map.of(
                "teammate", Map.of("type", "string",
                        "description", "Teammate name to shut down")
        );
        Map<String, Object> requestPlanSchema = Map.of(
                "teammate", Map.of("type", "string",
                        "description", "Teammate name to ask for a plan"),
                "task", Map.of("type", "string",
                        "description", "Task description, will be sent as message asking for plan")
        );
        Map<String, Object> reviewPlanSchema = new java.util.LinkedHashMap<>();
        reviewPlanSchema.put("request_id", Map.of("type", "string",
                "description", "request_id from a previous plan_approval_request (cron-like ID 'req_xxxxxx')"));
        reviewPlanSchema.put("approve", Map.of("type", "boolean",
                "description", "true to approve, false to reject"));
        reviewPlanSchema.put("feedback", Map.of("type", "string",
                "description", "Optional feedback (especially when rejecting)"));

        return List.of(
                new ToolDefinition(
                        "spawn_teammate",
                        "Spawn a new teammate agent in a daemon thread. " +
                                "The teammate runs independently with its own messages and " +
                                "communicates via the MessageBus.",
                        InputSchema.object(spawnSchema, "name", "role", "prompt")),
                new ToolDefinition(
                        "send_message",
                        "Send a plain message to a teammate's inbox via the MessageBus.",
                        InputSchema.object(sendSchema, "to", "content")),
                new ToolDefinition(
                        "check_inbox",
                        "Read all messages currently in lead's inbox (consume-on-read). " +
                                "Protocol responses (shutdown_response / plan_approval_response) " +
                                "are auto-routed to the registry.",
                        InputSchema.object(Map.of())),
                // s16:3 个新协议工具
                new ToolDefinition(
                        "request_shutdown",
                        "Send a shutdown_request to a teammate. " +
                                "The teammate will reply shutdown_response and exit gracefully.",
                        InputSchema.object(requestShutdownSchema, "teammate")),
                new ToolDefinition(
                        "request_plan",
                        "Ask a teammate to submit a plan first via submit_plan tool. " +
                                "Send a plain message describing the task; teammate responds " +
                                "with plan_approval_request which lead can review.",
                        InputSchema.object(requestPlanSchema, "teammate", "task")),
                new ToolDefinition(
                        "review_plan",
                        "Review a pending plan_approval request. Sends plan_approval_response " +
                                "to the requesting teammate and updates the protocol registry.",
                        InputSchema.object(reviewPlanSchema, "request_id", "approve"))
        );
    }

    /**
     * s22 D-10-D:1-arg execute 就够 —— teammate 是**跨线程**边界(spawn 到 worker pool),
     * spawn 时把 sid 一次性传进去(在 doSpawn 内部从 {@link com.xilidou.jooj.agent.SessionContext}
     * 读),Runnable 顶部再 push 一次 —— 这样 teammate 线程栈就有 sid,内部 tool 调用
     * 命中 {@code WebUserApprover} 时能冒泡到 lead 的 pending 队列。
     *
     * <p>Interrupt 覆盖:teammate 内部 outer while 也调 {@code isInterruptRequested(sid)},
     * lead 被 interrupt 时同 sid 下所有 teammate 下一个检查点一并停。
     */
    /**
     * s22 D-11:team tool 摘要 —— 按子命令展示动作。
     */
    @Override
    public String summary(ToolCall call) {
        if (call == null) return getName();
        Map<String, Object> args = call.getArguments() == null ? Map.of() : call.getArguments();
        return switch (call.getToolName()) {
            case "spawn_teammate" -> "👥 spawn " + args.getOrDefault("name", "?")
                    + " as " + args.getOrDefault("role", "?");
            case "send_message" -> "💬 → " + args.getOrDefault("to", "?");
            case "check_inbox" -> "📥 check inbox";
            case "request_shutdown" -> "🛑 shutdown " + args.getOrDefault("name", "?");
            case "request_plan" -> "📋 plan from " + args.getOrDefault("name", "?");
            case "review_plan" -> "📝 review plan " + args.getOrDefault("plan_id", "?");
            default -> call.getToolName();
        };
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            return switch (call.getToolName()) {
                case "spawn_teammate" -> doSpawn(call);
                case "send_message" -> doSend(call);
                case "check_inbox" -> doCheckInbox();
                case "request_shutdown" -> doRequestShutdown(call);
                case "request_plan" -> doRequestPlan(call);
                case "review_plan" -> doReviewPlan(call);
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

        // s22 D-10-D:从 SessionContext (ThreadLocal) 读 lead sid,一次性传给 teammate.spawn。
        // 跨线程边界,ThreadLocal 传不过去,必须显式传参。teammate 的 Runnable 顶部会重新 push。
        String parentSid = com.xilidou.jooj.agent.SessionContext.current();
        String result = teammate.spawn(name.toString(), role.toString(), prompt.toString(), parentSid);
        boolean success = result.startsWith("Spawned ");
        if (success) {
            log.info("[TeamTool] {}", result);
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
        // s16: 主动 check_inbox 时也要先路由协议响应,保证 pending_requests 状态正确,
        // 然后只展示给 LLM"剩下的 / 路由完之后保留的"消息
        List<Message> msgs = bus.readInbox(LEAD_NAME);
        if (msgs.isEmpty()) {
            return new ToolResult(true, "Inbox empty.");
        }
        List<Message> nonProtocol = routeProtocolResponses(msgs);
        if (nonProtocol.isEmpty()) {
            return new ToolResult(true, "Inbox contained only protocol responses (auto-routed).");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(nonProtocol.size()).append(" message(s) in lead's inbox:\n");
        for (Message m : nonProtocol) {
            sb.append("  ✉ from ").append(m.getFrom())
                    .append(" (").append(m.getType());
            // 协议请求(plan_approval_request)的 request_id 显式给 LLM 看
            String reqId = String.valueOf(m.getMetadata().getOrDefault("request_id", ""));
            if (!reqId.isBlank()) sb.append(" req:").append(reqId);
            sb.append("): ").append(m.getContent()).append("\n");
        }
        String out = sb.toString();
        if (out.endsWith("\n")) out = out.substring(0, out.length() - 1);
        return new ToolResult(true, out);
    }

    /**
     * s16:用 ProtocolRegistry 路由响应消息,把响应消息从列表里剔除返回。
     *
     * <p>对应上游 {@code consume_lead_inbox(route_protocol=True)}。
     */
    private List<Message> routeProtocolResponses(List<Message> msgs) {
        List<Message> nonProtocol = new ArrayList<>();
        for (Message m : msgs) {
            String type = m.getType();
            if ("shutdown_response".equals(type) || "plan_approval_response".equals(type)) {
                Map<String, Object> meta = m.getMetadata();
                String reqId = String.valueOf(meta.getOrDefault("request_id", ""));
                Object approveObj = meta.get("approve");
                boolean approve = approveObj instanceof Boolean b && b;
                protocols.match(type, reqId, approve);
                continue;
            }
            nonProtocol.add(m);
        }
        return nonProtocol;
    }

    // ─────────────────────────────────────────────────────────────
    //  s16:新协议工具
    // ─────────────────────────────────────────────────────────────

    private ToolResult doRequestShutdown(ToolCall call) {
        Object teammate = call.getArguments().get("teammate");
        if (teammate == null) return new ToolResult(false, "Error: 'teammate' is required");
        String name = teammate.toString();

        String reqId = protocols.register(
                ProtocolState.TYPE_SHUTDOWN, LEAD_NAME, name, "");
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("request_id", reqId);
        bus.send(LEAD_NAME, name, "Please shut down gracefully.",
                "shutdown_request", meta);
        String msg = "Shutdown request sent to " + name + " (req: " + reqId + ")";
        log.info("[TeamTool] {}", msg);
        return new ToolResult(true, msg);
    }

    private ToolResult doRequestPlan(ToolCall call) {
        Object teammate = call.getArguments().get("teammate");
        Object task = call.getArguments().get("task");
        if (teammate == null) return new ToolResult(false, "Error: 'teammate' is required");
        if (task == null) return new ToolResult(false, "Error: 'task' is required");

        // 这里不创建 ProtocolState —— request_plan 是普通指令性消息,
        // 真正的 protocol 在 teammate 调 submit_plan 时由 teammate 端创建。
        // 跟上游 run_request_plan 一致。
        bus.send(LEAD_NAME, teammate.toString(),
                "Please submit a plan for: " + task,
                "message");
        return new ToolResult(true,
                "Asked " + teammate + " to submit a plan. Wait for plan_approval_request in inbox.");
    }

    private ToolResult doReviewPlan(ToolCall call) {
        Object reqIdArg = call.getArguments().get("request_id");
        Object approveArg = call.getArguments().get("approve");
        if (reqIdArg == null) return new ToolResult(false, "Error: 'request_id' is required");
        if (approveArg == null) return new ToolResult(false, "Error: 'approve' is required");

        String reqId = reqIdArg.toString();
        boolean approve = approveArg instanceof Boolean b ? b
                : Boolean.parseBoolean(approveArg.toString());
        Object feedbackArg = call.getArguments().get("feedback");
        String feedback = feedbackArg != null ? feedbackArg.toString() : "";

        ProtocolState state = protocols.get(reqId);
        if (state == null) {
            return new ToolResult(false, "Request " + reqId + " not found");
        }
        if (!ProtocolState.PENDING.equals(state.getStatus())) {
            return new ToolResult(false, "Request " + reqId + " already " + state.getStatus());
        }
        // 先在 registry 里更新状态(lead 主动 review 等价于 lead 收到自己的 response)
        // 然后发 plan_approval_response 给原 sender
        ProtocolState matched = protocols.match("plan_approval_response", reqId, approve);
        if (matched == null) {
            return new ToolResult(false, "Failed to match request " + reqId);
        }
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("request_id", reqId);
        meta.put("approve", approve);
        String content = !feedback.isBlank() ? feedback
                : (approve ? "Approved" : "Rejected");
        bus.send(LEAD_NAME, state.getSender(), content,
                "plan_approval_response", meta);
        String result = "Plan " + (approve ? "approved" : "rejected") + " (" + reqId + ")";
        log.info("[TeamTool] {}", result);
        return new ToolResult(true, result);
    }
}
