package com.xilidou.marvis.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.MarvisProperties;
import com.xilidou.marvis.config.MarvisExecutors;
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
import com.xilidou.marvis.tasks.TaskRecord;
import com.xilidou.marvis.team.AutonomousIdle;
import com.xilidou.marvis.team.Message;
import com.xilidou.marvis.team.MessageBus;
import com.xilidou.marvis.team.ProtocolRegistry;
import com.xilidou.marvis.team.ProtocolState;
import com.xilidou.marvis.team.WorktreeService;
import com.xilidou.marvis.tool.ExecutionContext;
import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.tool.ToolDefinition;
import com.xilidou.marvis.tool.ToolRegistry;
import com.xilidou.marvis.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

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

    /** 队友"活动轮"上限 —— 真正调 LLM 的轮数。idle 等待不计入此上限。 */
    public static final int MAX_ACTIVE_TURNS = 30;

    /**
     * idle 状态轮询间隔(ms)的默认值,跟上游 {@code time.sleep(1)} 一致。
     * 实际值由 {@link MarvisProperties.Team#idlePollMs} 注入,这里保留为常量参考。
     *
     * @deprecated s17 后改用配置注入,见 {@link #idlePollMs}
     */
    @Deprecated
    public static final long IDLE_TICK_MS = 1000;

    /**
     * idle 等待最长时长(ms)的默认值。
     * 实际值由 {@link MarvisProperties.Team#idleTimeoutMs} 注入。
     *
     * @deprecated s17 后改用配置注入,见 {@link #idleTimeoutMs}
     */
    @Deprecated
    public static final long IDLE_MAX_MS = 5 * 60 * 1000L;

    private static final int MAX_TOKENS = 8000;

    /** 队友每轮 messages 截断窗口(只保最后 N 条,跟上游 messages[-20:] 一致)。 */
    private static final int MESSAGE_WINDOW = 20;

    private static final String PURPLE = "\033[35m";
    private static final String CYAN = "\033[36m";
    private static final String GRAY = "\033[90m";
    private static final String RESET = "\033[0m";

    /** Teammate 的 send_message 工具名 —— 跟队友间发消息共用。 */
    public static final String SEND_MESSAGE_TOOL = "send_message";

    /** Teammate 的 submit_plan 工具名 —— s16 新增。 */
    public static final String SUBMIT_PLAN_TOOL = "submit_plan";

    /**
     * s17:Teammate 可用的 task 工具白名单(从 s12 TasksTool 引入)。
     * 让队友能自己看 / 认领 / 完成 task,实现自组织。
     *
     * <p>不开放 {@code create_task / get_task} —— task 创建是 Lead 职责,
     * 队友只能在已有 task 池里挑活。
     */
    public static final Set<String> TEAMMATE_TASK_TOOLS = Set.of(
            "list_tasks", "claim_task", "complete_task"
    );

    /** 注册表防重名;同名 spawn 第二次会被拒绝。{@code true} = 活着,移除 = 退出。 */
    private final Map<String, Boolean> activeTeammates = new ConcurrentHashMap<>();

    private final AnthropicClient client;
    private final String model;
    private final ToolRegistry registry;
    private final ObjectMapper json;
    private final HookManager hooks;
    private final MessageBus bus;
    private final ProtocolRegistry protocols;
    private final ExecutorService workerExecutor;
    /** s17:自组织 idle 阶段的 scan + auto-claim 封装。 */
    private final AutonomousIdle autonomousIdle;
    /** s18:worktree 服务,claim 后查 task.worktree 字段需要拿对应路径。 */
    private final WorktreeService worktreeService;
    /** s18:claim_task 后读 task.worktree 字段需要 task 服务。 */
    private final com.xilidou.marvis.tasks.TaskService taskService;
    /** s17:idle 轮询间隔(ms),来自 marvis.team.idle-poll-ms。 */
    private final long idlePollMs;
    /** s17:idle 总超时(ms),来自 marvis.team.idle-timeout-ms。 */
    private final long idleTimeoutMs;

    public Teammate(AnthropicClient client,
                    ToolRegistry registry,
                    @Qualifier("marvisObjectMapper") ObjectMapper json,
                    HookManager hooks,
                    MessageBus bus,
                    ProtocolRegistry protocols,
                    @Qualifier(MarvisExecutors.TEAMMATE_BEAN) ExecutorService workerExecutor,
                    AutonomousIdle autonomousIdle,
                    WorktreeService worktreeService,
                    com.xilidou.marvis.tasks.TaskService taskService,
                    MarvisProperties props) {
        this.client = client;
        this.model = props.getAnthropic().getModel();
        this.registry = registry;
        this.json = json;
        this.hooks = hooks;
        this.bus = bus;
        this.protocols = protocols;
        this.workerExecutor = workerExecutor;
        this.autonomousIdle = autonomousIdle;
        this.worktreeService = worktreeService;
        this.taskService = taskService;
        this.idlePollMs = props.getTeam().getIdlePollMs();
        this.idleTimeoutMs = props.getTeam().getIdleTimeoutMs();
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

        Runnable teammateWork = () -> {
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
        };

        try {
            workerExecutor.submit(teammateWork);
        } catch (RejectedExecutionException e) {
            // 池满 —— 把注册表回退,return Error 给 caller
            activeTeammates.remove(name);
            log.warn("[Teammate] worker pool full, rejected spawn '{}': {}",
                    name, e.toString());
            return "Error: worker pool full (max concurrent bg/teammate tasks reached). " +
                    "Try again after some teammates finish or reduce parallelism.";
        }

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
                "When asked to plan first, call submit_plan(plan=\"...\") and wait for approval. " +
                "You can list and claim tasks from the board (list_tasks/claim_task/complete_task). " +
                "Check inbox for protocol messages (shutdown_request, plan_approval_response). " +
                "Be concise.";

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user(prompt));

        // s18: per-spawn worktree 状态。每个 spawn 一份独立 AtomicReference,
        // 跟 Python 闭包 wt_ctx={"path": None} 语义对应。线程安全(workerExecutor 复用 thread
        // 时本变量是 method-local,跟 thread 无关)。
        // 值更新点:
        //   - claim_task 工具执行成功后,从 task.worktree 字段读路径
        //   - complete_task 工具执行成功后,清回 null
        //   - auto-claim(IDLE 阶段)同样要更新
        AtomicReference<Path> currentCwd = new AtomicReference<>(null);
        AtomicReference<String> currentWorktreeName = new AtomicReference<>(null);

        List<ToolDef> tools = buildTeammateTools();
        String lastText = "";
        boolean shutdownRequested = false;
        int activeTurnTotal = 0;

        // s17: outer WORK ↔ IDLE 循环。WORK 跑完(LLM end_turn)进 IDLE,IDLE 期间扫看板
        // + 等 inbox。有新活回 WORK,无活超时退出。
        outer:
        while (!shutdownRequested && activeTurnTotal < MAX_ACTIVE_TURNS) {

            // ── 身份重注入(s17)──
            // compact 之后 messages 可能被压缩成一段摘要,LLM 失忆"我是谁"。
            // 简单粗暴判断:messages.size() <= 3 时 prepend identity。
            // 后期优化清单 #11:精准检测 [Conversation summary] 标记。
            if (messages.size() <= 3) {
                messages.add(0, MessageParam.user(
                        "<identity>You are '" + name + "', role: " + role +
                                ". Continue your work.</identity>"));
            }

            // ── WORK 阶段:内层最多 10 LLM call(防一次 work 跑太久) ──
            int workTurn = 0;
            boolean reachedEndTurn = false;
            while (workTurn < 10 && activeTurnTotal < MAX_ACTIVE_TURNS) {
                workTurn++;
                activeTurnTotal++;

                // 每轮先收件 + 协议分发
                DispatchResult d = consumeInboxAndDispatch(name, messages);
                if (d.shutdown) {
                    shutdownRequested = true;
                    break outer;
                }

                // 调 LLM
                List<MessageParam> window = trimWindow(messages);
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
                    log.warn("[Teammate {}] LLM call failed at turn {}: {}",
                            name, activeTurnTotal, e.toString());
                    shutdownRequested = true;
                    break outer;
                }
                messages.add(MessageParam.assistant(response.getContent()));
                lastText = extractLastText(response.getContent());

                if (!response.needsToolExecution()) {
                    reachedEndTurn = true;
                    break;     // out of WORK,进 IDLE
                }

                // 执行工具
                executeToolsInResponse(name, response, messages, currentCwd, currentWorktreeName);
            }

            // ── IDLE 阶段(s17 升级)──
            // 跑完一轮 WORK 后,WORK 因 end_turn 退出 → 进 IDLE 等新活;
            // 因 10 轮上限退出 → 也进 IDLE(让队友休息 + 看看新任务,而不是直接死);
            // 因 MAX_ACTIVE_TURNS 已耗尽 → 退出循环(收尾汇报)
            if (activeTurnTotal >= MAX_ACTIVE_TURNS) break;

            if (reachedEndTurn) {
                System.out.println(GRAY + "  [" + name + "] WORK done, entering IDLE..." + RESET);
            } else {
                System.out.println(GRAY + "  [" + name + "] WORK budget reached, entering IDLE..." + RESET);
            }
            IdleResult idle = idlePoll(name, messages, currentCwd, currentWorktreeName);
            if (idle.shutdown) {
                shutdownRequested = true;
                break;
            }
            if (idle.timeout) {
                // 超时 = 没新活,退出
                System.out.println(GRAY + "  [" + name + "] IDLE timeout, exiting" + RESET);
                break;
            }
            // idle 找到活了(inbox 或 claim 到 task),回到 outer while 继续 WORK
        }

        // 收尾:把 last assistant text 发给 lead 当 result
        if (lastText == null || lastText.isBlank()) {
            lastText = "(teammate " + name + " finished without producing text)";
        }
        bus.send(name, "lead", lastText, "result");
        System.out.println(PURPLE + "[Teammate " + name + " done, summary sent to lead]" + RESET);
    }

    /**
     * 抽出 WORK 阶段的工具执行块,让 runLoop 主流程更清楚。
     *
     * <p>s18 加 currentCwdRef 参数:
     * <ul>
     *   <li>构造 {@link ExecutionContext} 让需要 cwd 的工具(bash/filesystem)按 worktree 路径执行</li>
     *   <li>claim_task 成功后从 task.worktree 字段读路径,更新 cwdRef</li>
     *   <li>complete_task 成功后清 cwdRef 回 null(下次 claim 重设)</li>
     * </ul>
     */
    private void executeToolsInResponse(String name, CreateMessageResponse response,
                                         List<MessageParam> messages,
                                         AtomicReference<Path> currentCwd,
                                         AtomicReference<String> currentWorktreeName) {
        List<ToolResultBlock> results = new ArrayList<>();
        for (ToolUseBlock tu : response.toolUses()) {
            Map<String, Object> args = parseToolInput(tu);
            System.out.println(CYAN + "  [" + name + " · " + tu.getName() + "] " + args + RESET);

            Optional<String> blocked = hooks.triggerPreToolUse(tu);
            if (blocked.isPresent()) {
                results.add(ToolResultBlock.ofText(tu.getId(), blocked.get()));
                continue;
            }

            // s18:每次工具调用都构造 ctx,工具按 ctx.cwd 决定行为
            ExecutionContext ctx = currentCwd.get() != null
                    ? ExecutionContext.inWorktree(name, currentWorktreeName.get(), currentCwd.get())
                    : ExecutionContext.forTeammate(name);

            String output;
            if (SEND_MESSAGE_TOOL.equals(tu.getName())) {
                output = handleSendMessage(name, args);
            } else if (SUBMIT_PLAN_TOOL.equals(tu.getName())) {
                output = handleSubmitPlan(name, args);
            } else if (TEAMMATE_TASK_TOOLS.contains(tu.getName())) {
                // s17: list_tasks / claim_task / complete_task —— 走 ToolRegistry,
                // 但传入 owner=name 让 claim_task 用队友身份认领
                Map<String, Object> withOwner = new HashMap<>(args);
                if ("claim_task".equals(tu.getName())) {
                    withOwner.put("owner", name);
                }
                ToolResult r = registry.execute(new ToolCall(tu.getName(), withOwner), ctx);
                output = r.getOutput();
                // s18:claim_task 成功后切 cwd 到 task.worktree;complete 后清
                if ("claim_task".equals(tu.getName()) && output.startsWith("Claimed ")) {
                    Object taskIdArg = withOwner.get("task_id");
                    if (taskIdArg != null) {
                        applyClaimedTaskWorktree(taskIdArg.toString(), name,
                                currentCwd, currentWorktreeName);
                    }
                } else if ("complete_task".equals(tu.getName()) && output.startsWith("Completed ")) {
                    if (currentCwd.get() != null) {
                        System.out.println(GRAY + "  [" + name + "] worktree cwd cleared after complete" + RESET);
                    }
                    currentCwd.set(null);
                    currentWorktreeName.set(null);
                }
            } else if (Subagent.DEFAULT_INCLUDED_TOOLS.contains(tu.getName())) {
                ToolResult r = registry.execute(new ToolCall(tu.getName(), args), ctx);
                output = r.getOutput();
            } else {
                output = "Error: tool '" + tu.getName() + "' not available to teammates";
            }
            results.add(ToolResultBlock.ofText(tu.getId(), output));
        }
        messages.add(MessageParam.toolResults(results));
    }

    /**
     * s18:claim_task 成功后,从 task 读 worktree 字段更新 cwd。
     *
     * <p>找不到 task / 没绑 worktree → 静默清 cwd(队友在共享 workdir 干活,跟 s17 行为一致)。
     */
    private void applyClaimedTaskWorktree(String taskId, String name,
                                           AtomicReference<Path> currentCwd,
                                           AtomicReference<String> currentWorktreeName) {
        TaskRecord task = taskService.get(taskId).orElse(null);
        if (task == null) {
            currentCwd.set(null);
            currentWorktreeName.set(null);
            return;
        }
        String wtName = task.getWorktree();
        if (wtName == null || wtName.isBlank()) {
            currentCwd.set(null);
            currentWorktreeName.set(null);
            return;
        }
        Path wtPath = worktreeService.pathFor(wtName);
        if (!java.nio.file.Files.isDirectory(wtPath)) {
            log.warn("[Teammate {}] task {} bound to worktree {} but path missing: {}; falling back to user.dir",
                    name, taskId, wtName, wtPath);
            currentCwd.set(null);
            currentWorktreeName.set(null);
            return;
        }
        currentCwd.set(wtPath);
        currentWorktreeName.set(wtName);
        System.out.println("\033[33m  [" + name + "] cwd → " + wtPath + " (worktree:" + wtName + ")" + RESET);
    }

    /**
     * 消费一次 inbox,先按 type 分发协议消息,再把非协议的揉成一条 user message 加到 messages。
     *
     * <p>对应上游 {@code handle_inbox_message}:
     * <ul>
     *   <li>{@code shutdown_request} → 自动回 {@code shutdown_response approve=true},
     *       返回 shutdown=true 告诉调用方退出 loop</li>
     *   <li>{@code plan_approval_response} → 把 "[Plan approved/rejected: feedback]" 注入 messages</li>
     *   <li>其他类型(普通 message / result / 未知)→ 收集为 non-protocol</li>
     * </ul>
     *
     * <p>非协议消息作为单条 {@code <inbox>...</inbox>} user message 追加到 messages,
     * 让 LLM 下一轮看到。
     */
    private DispatchResult consumeInboxAndDispatch(String name, List<MessageParam> messages) {
        List<Message> inbox = bus.readInbox(name);
        if (inbox.isEmpty()) return new DispatchResult(false);

        boolean shutdown = false;
        List<Message> nonProtocol = new ArrayList<>();
        for (Message msg : inbox) {
            if (dispatchProtocolMessage(name, msg, messages)) {
                shutdown = true;
                // 不 break;后续协议消息仍要处理(虽然之后 loop 会退出,
                // 但万一同 batch 里有 plan_approval_response 也想 inject)
                continue;
            }
            // 非协议
            String type = msg.getType();
            if (!"shutdown_request".equals(type) && !"plan_approval_response".equals(type)) {
                nonProtocol.add(msg);
            }
        }

        if (!nonProtocol.isEmpty()) {
            messages.add(MessageParam.user(formatInboxAsUserText(nonProtocol)));
            System.out.println(GRAY + "  [" + name + "] inbox " + nonProtocol.size()
                    + " non-protocol msg(s)" + RESET);
        }
        return new DispatchResult(shutdown);
    }

    /**
     * 按消息 type 分发协议消息。
     *
     * @return true 表示这是 shutdown_request,调用方应停止 loop
     */
    private boolean dispatchProtocolMessage(String name, Message msg, List<MessageParam> messages) {
        String type = msg.getType();
        Map<String, Object> meta = msg.getMetadata() != null ? msg.getMetadata() : Map.of();
        String reqId = String.valueOf(meta.getOrDefault("request_id", ""));

        if ("shutdown_request".equals(type)) {
            // 直接回 shutdown_response approve=true 然后退出
            Map<String, Object> respMeta = new LinkedHashMap<>();
            respMeta.put("request_id", reqId);
            respMeta.put("approve", Boolean.TRUE);
            bus.send(name, "lead", "Shutting down gracefully.",
                    "shutdown_response", respMeta);
            System.out.println(PURPLE + "  [protocol] " + name + " approved shutdown ("
                    + reqId + ")" + RESET);
            return true;
        }

        if ("plan_approval_response".equals(type)) {
            Object approveObj = meta.get("approve");
            boolean approved = approveObj instanceof Boolean b && b;
            String injection;
            if (approved) {
                injection = "[Plan approved] (" + reqId + ") Proceed with the task.";
            } else {
                injection = "[Plan rejected] (" + reqId + ") Feedback: " + msg.getContent();
            }
            messages.add(MessageParam.user(injection));
            System.out.println((approved ? "\033[32m" : "\033[31m") + "  [protocol] " + name
                    + " plan " + (approved ? "approved" : "rejected") + " (" + reqId + ")" + RESET);
            return false;
        }

        return false;
    }

    /**
     * s17 idle poll —— 替代 s16 的 idleWaitForInbox。
     *
     * <p>每 {@link #idlePollMs} 毫秒做一次:
     * <ol>
     *   <li>消费 inbox 并 dispatch 协议消息(shutdown 直接退出)</li>
     *   <li>inbox 有非协议消息 → 注入 messages,返回 shutdown=false / timeout=false 让 outer 进入下一轮 WORK</li>
     *   <li>inbox 空 → {@link AutonomousIdle#tryClaim} 主动看板找任务</li>
     *   <li>claim 到任务 → 注入 [auto-claimed Task X: ...] user message,返回继续 WORK</li>
     *   <li>都没有 → sleep 下一轮</li>
     * </ol>
     *
     * <p>累计超过 {@link #idleTimeoutMs} 没有任何活就 timeout 退出。
     *
     * <p>s18:auto-claim 时同步更新 currentCwd/currentWorktreeName 引用。
     */
    private IdleResult idlePoll(String name, List<MessageParam> messages,
                                AtomicReference<Path> currentCwd,
                                AtomicReference<String> currentWorktreeName) {
        long deadline = System.currentTimeMillis() + idleTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(idlePollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new IdleResult(true, false);   // 视为 shutdown
            }

            int prevSize = messages.size();

            // 1. 收件 + 协议分发(优先于扫看板)
            DispatchResult d = consumeInboxAndDispatch(name, messages);
            if (d.shutdown) return new IdleResult(true, false);
            if (messages.size() > prevSize) {
                // inbox 来了 messages 增长 → 有活了,回 WORK
                return new IdleResult(false, false);
            }

            // 2. inbox 空 → 主动扫看板找活(s17 核心)
            Optional<TaskRecord> claimed = autonomousIdle.tryClaim(name);
            if (claimed.isPresent()) {
                TaskRecord t = claimed.get();
                String injection = "[auto-claimed] Task " + t.getId() + ": " +
                        t.getSubject() +
                        (t.getDescription() != null && !t.getDescription().isBlank()
                                ? "\n" + t.getDescription() : "");
                messages.add(MessageParam.user(injection));
                System.out.println("\033[32m  [idle] " + name + " auto-claimed: "
                        + t.getSubject() + RESET);
                // s18:auto-claim 也要更新 cwd
                applyClaimedTaskWorktree(t.getId(), name, currentCwd, currentWorktreeName);
                return new IdleResult(false, false);
            }
        }
        // 超时
        log.info("[Teammate {}] idle timeout {} ms, exiting", name, idleTimeoutMs);
        return new IdleResult(false, true);
    }

    /**
     * Teammate 调 submit_plan 工具 —— 提交计划给 lead 审批。
     *
     * <p>注意:跟上游一致,这里**没做执行 gate** —— 队友提交完 plan 后 LLM 仍能调
     * bash/write_file。真正阻塞要等 LLM 自己看到 [Plan approved] 注入才该动手。
     * 后期优化清单已记录"加 plan_approval 执行 gate"。
     */
    private String handleSubmitPlan(String fromName, Map<String, Object> args) {
        Object plan = args.get("plan");
        if (plan == null || plan.toString().isBlank()) {
            return "Error: 'plan' is required";
        }
        String planText = plan.toString();
        String reqId = protocols.register(
                ProtocolState.TYPE_PLAN_APPROVAL, fromName, "lead", planText);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("request_id", reqId);
        bus.send(fromName, "lead", planText, "plan_approval_request", meta);
        return "Plan submitted (" + reqId + "). Waiting for approval...";
    }

    /** dispatch / idle 状态机的简单返回类型。 */
    private record DispatchResult(boolean shutdown) {}
    private record IdleResult(boolean shutdown, boolean timeout) {}

    /** 工具白名单 = Subagent 现有 + send_message + submit_plan(s16) + task 三件套(s17)。 */
    private List<ToolDef> buildTeammateTools() {
        List<ToolDef> out = new ArrayList<>();
        for (ToolDefinition def : registry.getAllTools()) {
            // s06 文件 / bash 系
            if (Subagent.DEFAULT_INCLUDED_TOOLS.contains(def.getName())) {
                out.add(new ToolDef(def.getName(), def.getDescription(), def.getInputSchema()));
                continue;
            }
            // s17:开放 list_tasks / claim_task / complete_task 给队友自组织
            if (TEAMMATE_TASK_TOOLS.contains(def.getName())) {
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

        // s16: 加 submit_plan 内置工具
        Map<String, Object> planSchema = Map.of(
                "plan", Map.of("type", "string",
                        "description",
                        "Plan text to submit to lead for approval. " +
                                "Wait for [Plan approved] / [Plan rejected] response before acting.")
        );
        out.add(new ToolDef(SUBMIT_PLAN_TOOL,
                "Submit a plan to lead for approval. Waits for response before continuing risky work.",
                InputSchema.object(planSchema, "plan")));
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
