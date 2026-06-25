package com.xilidou.marvis.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.MarvisProperties;
import com.xilidou.marvis.hook.HookManager;
import com.xilidou.marvis.http.AnthropicClient;
import com.xilidou.marvis.http.dto.CreateMessageRequest;
import com.xilidou.marvis.http.dto.CreateMessageResponse;
import com.xilidou.marvis.http.dto.InputSchema;
import com.xilidou.marvis.http.dto.MessageParam;
import com.xilidou.marvis.http.dto.TextBlock;
import com.xilidou.marvis.http.dto.ToolDef;
import com.xilidou.marvis.http.dto.ToolResultBlock;
import com.xilidou.marvis.http.dto.ToolUseBlock;
import com.xilidou.marvis.team.Message;
import com.xilidou.marvis.team.MessageBus;
import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.tool.ToolDefinition;
import com.xilidou.marvis.tool.ToolRegistry;
import com.xilidou.marvis.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teammate —— s15 多 agent 队友:**daemon thread + 简化 agent loop + 异步消息通信**。
 *
 * <p>对应 Python 上游 s15 的 {@code spawn_teammate_thread(name, role, prompt)}。
 *
 * <h3>跟 {@link Subagent} 的本质区别</h3>
 *
 * <table>
 *   <tr><th></th><th>{@link Subagent}(s06)</th><th>Teammate(s15)</th></tr>
 *   <tr><td>生命周期</td><td>同步:派出 → 等结果 → 销毁</td><td>异步:派出后立即返回,daemon thread 跑</td></tr>
 *   <tr><td>通信</td><td>只回传摘要</td><td>{@link MessageBus} 双向收发</td></tr>
 *   <tr><td>spawn 调用</td><td>父 agent 同步阻塞</td><td>父 agent 立即拿到 placeholder 继续工作</td></tr>
 *   <tr><td>类比</td><td>函数调用</td><td>actor / 微服务</td></tr>
 * </table>
 *
 * <p>因此本类<b>不复用</b> Subagent —— 复用会让 Subagent 同步语义被破坏,
 * 测试与 s06 行为冲突。共享只发生在 SUB_TOOLS 白名单(教学版决策"松一点":
 * marvis Subagent 现有白名单 + send_message 加进来)。
 *
 * <h3>简化 agent loop</h3>
 *
 * <pre>
 *   for turn in 0 .. MAX_TURNS:
 *     inbox = bus.readInbox(name)            ← 每轮先收件
 *     if inbox: messages.append(<inbox>...)
 *     response = LLM(messages, sub_tools)
 *     if response.stop_reason != tool_use: break
 *     execute tools(包括 send_message)
 *   # 收尾:把最后 assistant 的 text 发回 lead 当 result
 *   bus.send(name, "lead", finalText, "result")
 * </pre>
 *
 * <h3>跟上游 5 个简化决策</h3>
 *
 * <ol>
 *   <li>教学版限 {@link #MAX_TURNS} 轮 — real CC 用 idle loop(s17 主题)</li>
 *   <li>read+unlink race 上游有 — marvis MessageBus 用 ReentrantLock 修了</li>
 *   <li>无权限冒泡 — real CC 双向 poller,marvis 暂不做(s16 主题)</li>
 *   <li>无 shutdown 协议 — daemon thread 跑完 MAX_TURNS 自然退出(s16 主题)</li>
 *   <li>Lead inbox 在 processOneQuery 末尾 drain 一次 — real CC 1s 后台 poller 自动注入</li>
 * </ol>
 *
 * <h3>线程模型</h3>
 *
 * <p>{@link #spawn} 立即返回,真实工作在 {@code marvis-teammate-<name>} daemon thread 跑。
 * {@link #activeTeammates} 注册表防重名 — 同名队友存在时拒绝再 spawn。
 *
 * <h3>不暴露给 Teammate 的工具</h3>
 *
 * <p>跟上游一致 + marvis 实际:
 * <ul>
 *   <li>{@code task} / {@code spawn_teammate} —— 防递归</li>
 *   <li>{@code todo_write} —— 父 agent 的 todo 不该被队友改</li>
 *   <li>{@code create_task} 等 task 系列 —— 跟 Subagent 同理</li>
 *   <li>{@code schedule_cron} 等 cron 系列 —— 队友不该排定时</li>
 * </ul>
 *
 * <p>队友可见 = {@link Subagent#DEFAULT_INCLUDED_TOOLS} + {@code send_message}。
 */
@Component
@Slf4j
public class Teammate {

    /** 队友最大轮数(防 infinite loop)。教学版硬限,s17 改成 idle loop 后取消。 */
    public static final int MAX_TURNS = 10;

    private static final int MAX_TOKENS = 8000;

    /** 队友每轮 messages 截断窗口(只保最后 N 条,跟上游 messages[-20:] 一致)。 */
    private static final int MESSAGE_WINDOW = 20;

    private static final String PURPLE = "\033[35m";
    private static final String CYAN = "\033[36m";
    private static final String GRAY = "\033[90m";
    private static final String RESET = "\033[0m";

    /** Teammate 的 send_message 工具名 —— 跟队友间发消息共用。 */
    public static final String SEND_MESSAGE_TOOL = "send_message";

    /** 注册表防重名;同名 spawn 第二次会被拒绝。{@code true} = 活着,移除 = 退出。 */
    private final Map<String, Boolean> activeTeammates = new ConcurrentHashMap<>();

    private final AnthropicClient client;
    private final String model;
    private final ToolRegistry registry;
    private final ObjectMapper json;
    private final HookManager hooks;
    private final MessageBus bus;

    public Teammate(AnthropicClient client,
                    ToolRegistry registry,
                    @Qualifier("marvisObjectMapper") ObjectMapper json,
                    HookManager hooks,
                    MessageBus bus,
                    MarvisProperties props) {
        this.client = client;
        this.model = props.getAnthropic().getModel();
        this.registry = registry;
        this.json = json;
        this.hooks = hooks;
        this.bus = bus;
    }

    // ─────────────────────────────────────────────────────────────
    //  API
    // ─────────────────────────────────────────────────────────────

    /**
     * 派一个新队友。立即返回 status 字符串,真实工作在 daemon thread 跑。
     *
     * @return {@code "Spawned <name> as <role>"} 成功;{@code "Error: <reason>"} 失败
     *         (重名 / 参数缺失等)
     */
    public String spawn(String name, String role, String prompt) {
        if (name == null || name.isBlank()) return "Error: name must not be blank";
        if (role == null || role.isBlank()) return "Error: role must not be blank";
        if (prompt == null || prompt.isBlank()) return "Error: prompt must not be blank";
        if ("lead".equalsIgnoreCase(name)) return "Error: 'lead' is reserved";
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return "Error: name must not contain path separators";
        }

        // 注册表防重名
        if (activeTeammates.putIfAbsent(name, Boolean.TRUE) != null) {
            return "Error: teammate '" + name + "' already exists";
        }

        Thread t = new Thread(() -> {
            try {
                runLoop(name, role, prompt);
            } catch (Exception e) {
                log.error("[Teammate {}] crashed", name, e);
                bus.send(name, "lead",
                        "Teammate " + name + " crashed: " + e.getMessage(), "error");
            } finally {
                activeTeammates.remove(name);
                log.info("[Teammate {}] exited", name);
            }
        }, "marvis-teammate-" + name);
        t.setDaemon(true);
        t.start();

        log.info("[Teammate] spawned {} as '{}'", name, role);
        return "Spawned " + name + " as " + role;
    }

    /** 当前活跃队友 name 列表。 */
    public Set<String> activeNames() {
        return Set.copyOf(activeTeammates.keySet());
    }

    /** 测试 / 监控用。 */
    public boolean isActive(String name) {
        return activeTeammates.getOrDefault(name, Boolean.FALSE);
    }

    // ─────────────────────────────────────────────────────────────
    //  daemon thread loop
    // ─────────────────────────────────────────────────────────────

    private void runLoop(String name, String role, String prompt) {
        System.out.println();
        System.out.println(PURPLE + "[Teammate " + name + " spawned as " + role + "]" + RESET);

        String system = "You are '" + name + "', a " + role + ". " +
                "Use tools to complete tasks. " +
                "Send results to the lead via send_message(to=\"lead\", content=...). " +
                "Be concise.";

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user(prompt));

        List<ToolDef> tools = buildTeammateTools();
        String lastText = "";

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            // 1. 每轮先收件,有 inbox 就追加成 user message
            List<Message> inbox = bus.readInbox(name);
            if (!inbox.isEmpty()) {
                messages.add(MessageParam.user(formatInboxAsUserText(inbox)));
                System.out.println(GRAY + "  [" + name + "] inbox " + inbox.size() + " msg" + RESET);
            }

            // 2. 截窗口防 history 爆 token
            List<MessageParam> window = trimWindow(messages);

            // 3. 调 LLM
            CreateMessageRequest request = CreateMessageRequest.builder()
                    .model(model)
                    .system(system)
                    .messages(window)
                    .tools(tools)
                    .maxTokens(MAX_TOKENS)
                    .build();

            CreateMessageResponse response;
            try {
                response = client.createMessage(request);
            } catch (Exception e) {
                log.warn("[Teammate {}] LLM call failed at turn {}: {}", name, turn, e.toString());
                break;
            }
            messages.add(MessageParam.assistant(response.getContent()));
            lastText = extractLastText(response.getContent());

            if (!response.needsToolExecution()) break;

            // 4. 执行工具(含 send_message 内置 + 注册表里的工具白名单)
            List<ToolResultBlock> results = new ArrayList<>();
            for (ToolUseBlock tu : response.toolUses()) {
                Map<String, Object> args = parseToolInput(tu);
                System.out.println(CYAN + "  [" + name + " · " + tu.getName() + "] " + args + RESET);

                Optional<String> blocked = hooks.triggerPreToolUse(tu);
                if (blocked.isPresent()) {
                    results.add(ToolResultBlock.ofText(tu.getId(), blocked.get()));
                    continue;
                }

                String output;
                if (SEND_MESSAGE_TOOL.equals(tu.getName())) {
                    output = handleSendMessage(name, args);
                } else if (Subagent.DEFAULT_INCLUDED_TOOLS.contains(tu.getName())) {
                    ToolResult r = registry.execute(new ToolCall(tu.getName(), args));
                    output = r.getOutput();
                } else {
                    output = "Error: tool '" + tu.getName() + "' not available to teammates";
                }
                results.add(ToolResultBlock.ofText(tu.getId(), output));
            }
            messages.add(MessageParam.toolResults(results));
        }

        // 5. 收尾:把 last assistant text 发给 lead 当 result
        if (lastText == null || lastText.isBlank()) {
            lastText = "(teammate " + name + " finished without producing text)";
        }
        bus.send(name, "lead", lastText, "result");
        System.out.println(PURPLE + "[Teammate " + name + " done, summary sent to lead]" + RESET);
    }

    /** 工具白名单 = Subagent 现有 + send_message。 */
    private List<ToolDef> buildTeammateTools() {
        List<ToolDef> out = new ArrayList<>();
        for (ToolDefinition def : registry.getAllTools()) {
            if (Subagent.DEFAULT_INCLUDED_TOOLS.contains(def.getName())) {
                out.add(new ToolDef(def.getName(), def.getDescription(), def.getInputSchema()));
            }
        }
        // 加 send_message 内置工具
        Map<String, Object> sendSchema = Map.of(
                "to", Map.of("type", "string",
                        "description", "Recipient agent name (e.g. 'lead' or another teammate's name)"),
                "content", Map.of("type", "string",
                        "description", "Message content text")
        );
        out.add(new ToolDef(SEND_MESSAGE_TOOL,
                "Send a message to another agent (lead or teammate) via the MessageBus.",
                InputSchema.object(sendSchema, "to", "content")));
        return out;
    }

    private String handleSendMessage(String fromName, Map<String, Object> args) {
        Object to = args.get("to");
        Object content = args.get("content");
        if (to == null) return "Error: 'to' is required";
        if (content == null) return "Error: 'content' is required";
        try {
            bus.send(fromName, to.toString(), content.toString(), "message");
            return "Sent to " + to;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String formatInboxAsUserText(List<Message> inbox) {
        StringBuilder sb = new StringBuilder("<inbox>\n");
        for (Message m : inbox) {
            sb.append("From ").append(m.getFrom())
                    .append(" (").append(m.getType()).append("): ")
                    .append(m.getContent()).append("\n");
        }
        sb.append("</inbox>");
        return sb.toString();
    }

    private List<MessageParam> trimWindow(List<MessageParam> messages) {
        if (messages.size() <= MESSAGE_WINDOW) return messages;
        return new ArrayList<>(messages.subList(messages.size() - MESSAGE_WINDOW, messages.size()));
    }

    private static String extractLastText(List<? extends com.xilidou.marvis.http.dto.ContentBlock> content) {
        if (content == null || content.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (com.xilidou.marvis.http.dto.ContentBlock b : content) {
            if (b instanceof TextBlock t && t.getText() != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(t.getText());
            }
        }
        return sb.toString();
    }

    private Map<String, Object> parseToolInput(ToolUseBlock toolUse) {
        try {
            Map<String, Object> converted = json.convertValue(toolUse.getInput(),
                    new TypeReference<>() {});
            return converted != null ? converted : new HashMap<>();
        } catch (Exception e) {
            log.error("[Teammate] parse tool input for {} failed: {}",
                    toolUse.getName(), e.getMessage());
            return new HashMap<>();
        }
    }
}
