package com.xilidou.jooj.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.tasks.TaskRecord;
import com.xilidou.jooj.tasks.TaskService;
import com.xilidou.jooj.tasks.TaskStatus;
import com.xilidou.jooj.tool.Tool;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TasksTool —— s12 Task System 的 5 个工具,严格对齐上游 [s12_task_system/code.py]。
 *
 * <p>对应 Python 的 5 个 {@code TOOLS} 条目 + {@code TOOL_HANDLERS}:
 * <ol>
 *   <li>{@code create_task(subject*, description?, blockedBy?)}</li>
 *   <li>{@code list_tasks()} 无参</li>
 *   <li>{@code get_task(task_id*)}</li>
 *   <li>{@code claim_task(task_id*)}</li>
 *   <li>{@code complete_task(task_id*)}</li>
 * </ol>
 *
 * <p>跟 {@link TodoTool} 同模式 —— 一个 {@link Tool} 实现暴露多个 {@link ToolDefinition},
 * 用 switch 分派 execute。
 *
 * <h3>错误是字符串,不抛异常</h3>
 *
 * <p>{@link TaskService#claim} / {@link TaskService#complete} 失败时返回**人类可读字符串**
 * (NL 错误如 {@code "Task X is in_progress, cannot claim"})。本工具把它们包成
 * {@link ToolResult#isSuccess()}=false 的 {@link ToolResult},LLM 看到 string 自我纠正。
 *
 * <h3>不依赖 Subagent / RecoveryCoordinator</h3>
 *
 * <p>本类只依赖 {@link TaskService},不调 Subagent / Permission / Recovery ——
 * 跟上游 README 强调的"独立层,自然组合"一致。
 */
@Component
@Slf4j
public class TasksTool implements Tool {

    /** ANSI 颜色 —— 跟 Python 的 print 颜色一致(蓝/青/绿/黄)。 */
    private static final String BLUE = "\033[34m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RESET = "\033[0m";

    private final TaskService service;
    private final ObjectMapper json;

    @Autowired
    public TasksTool(TaskService service,
                     @Qualifier("joojObjectMapper") ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @Override
    public String getName() {
        return "tasks";
    }

    @Override
    public String getDescription() {
        return "File-persisted task graph with blockedBy dependencies. " +
                "Use to plan / claim / complete sub-tasks (s12).";
    }

    @Override
    public List<ToolDefinition> getTools() {
        // create_task
        Map<String, Object> createSchema = Map.of(
                "subject", Map.of(
                        "type", "string",
                        "description", "Short title for the task"),
                "description", Map.of(
                        "type", "string",
                        "description", "Detailed description (optional)"),
                "blockedBy", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Task IDs that must complete first (optional)")
        );

        // 单 task_id 的工具(get / claim / complete)共用同一个 schema
        Map<String, Object> taskIdSchema = Map.of(
                "task_id", Map.of(
                        "type", "string",
                        "description", "Task ID, e.g. task_1729000000_3812")
        );

        return List.of(
                new ToolDefinition(
                        "create_task",
                        "Create a new task with optional blockedBy dependencies.",
                        InputSchema.object(createSchema, "subject")),
                new ToolDefinition(
                        "list_tasks",
                        "List all tasks with status, owner, and dependencies.",
                        InputSchema.object(Map.of())),
                new ToolDefinition(
                        "get_task",
                        "Get full details of a specific task by ID.",
                        InputSchema.object(taskIdSchema, "task_id")),
                new ToolDefinition(
                        "claim_task",
                        "Claim a pending task. Sets owner, changes status to in_progress.",
                        InputSchema.object(taskIdSchema, "task_id")),
                new ToolDefinition(
                        "complete_task",
                        "Complete an in-progress task. Reports unblocked downstream tasks.",
                        InputSchema.object(taskIdSchema, "task_id"))
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            return switch (call.getToolName()) {
                case "create_task" -> doCreate(call);
                case "list_tasks" -> doList();
                case "get_task" -> doGet(call);
                case "claim_task" -> doClaim(call);
                case "complete_task" -> doComplete(call);
                default -> new ToolResult(false, "Unknown tool: " + call.getToolName());
            };
        } catch (IllegalArgumentException e) {
            return new ToolResult(false, "Error: " + e.getMessage());
        } catch (Exception e) {
            log.error("[Tasks] tool {} failed", call.getToolName(), e);
            return new ToolResult(false, "Error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Handlers
    // ─────────────────────────────────────────────────────────────

    private ToolResult doCreate(ToolCall call) {
        Object subjectArg = call.getArguments().get("subject");
        if (subjectArg == null) {
            return new ToolResult(false, "Error: 'subject' argument is required");
        }
        String subject = subjectArg.toString();
        if (subject.isBlank()) {
            return new ToolResult(false, "Error: 'subject' must not be blank");
        }

        Object descArg = call.getArguments().get("description");
        String description = descArg == null ? "" : descArg.toString();

        @SuppressWarnings("unchecked")
        List<String> blockedBy = call.getArguments().get("blockedBy") instanceof List<?> raw
                ? new ArrayList<>(raw.stream().map(Object::toString).toList())
                : new ArrayList<>();

        String id = service.create(subject, description, blockedBy);
        String depsSuffix = blockedBy.isEmpty()
                ? ""
                : " (blockedBy: " + String.join(", ", blockedBy) + ")";
        // s23 P1c: println → log。tool_result 已经带 "Created ..." 全部信息回给 LLM。
        log.info("[TasksTool] create id={} subject={}{}", id, subject, depsSuffix);
        return new ToolResult(true,
                "Created " + id + ": " + subject + depsSuffix);
    }

    private ToolResult doList() {
        List<TaskRecord> tasks = service.list();
        if (tasks.isEmpty()) {
            return new ToolResult(true, "No tasks. Use create_task to add some.");
        }
        StringBuilder sb = new StringBuilder();
        for (TaskRecord t : tasks) {
            String icon = iconFor(t.getStatus());
            String owner = t.getOwner() != null ? " [" + t.getOwner() + "]" : "";
            String deps = t.getBlockedBy() != null && !t.getBlockedBy().isEmpty()
                    ? " (blockedBy: " + String.join(", ", t.getBlockedBy()) + ")"
                    : "";
            // s18:绑了 worktree 的 task 后缀显式标记 (wt:<name>)
            String wt = t.getWorktree() != null && !t.getWorktree().isBlank()
                    ? " (wt:" + t.getWorktree() + ")"
                    : "";
            // 缩进 2 空格,跟 Python 一致
            sb.append("  ").append(icon).append(' ')
                    .append(t.getId()).append(": ").append(t.getSubject())
                    .append(" [").append(t.getStatus().getValue()).append("]")
                    .append(owner).append(deps).append(wt).append('\n');
        }
        // 去掉最后一个 \n
        String out = sb.toString();
        if (out.endsWith("\n")) out = out.substring(0, out.length() - 1);
        return new ToolResult(true, out);
    }

    private ToolResult doGet(ToolCall call) {
        Object idArg = call.getArguments().get("task_id");
        if (idArg == null) {
            return new ToolResult(false, "Error: 'task_id' argument is required");
        }
        String id = idArg.toString();

        Optional<TaskRecord> task = service.get(id);
        if (task.isEmpty()) {
            // 跟上游 Python `f"Error: Task {task_id} not found"` 一致
            return new ToolResult(false, "Error: Task " + id + " not found");
        }
        try {
            String body = json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(task.get());
            return new ToolResult(true, body);
        } catch (JsonProcessingException e) {
            return new ToolResult(false, "Error: failed to serialize task: " + e.getMessage());
        }
    }

    private ToolResult doClaim(ToolCall call) {
        Object idArg = call.getArguments().get("task_id");
        if (idArg == null) {
            return new ToolResult(false, "Error: 'task_id' argument is required");
        }
        String id = idArg.toString();

        String result = service.claim(id, TaskService.DEFAULT_OWNER);
        boolean success = result.startsWith("Claimed ");
        if (success) {
            log.info("[TasksTool] {}", result);
        }
        return new ToolResult(success, result);
    }

    private ToolResult doComplete(ToolCall call) {
        Object idArg = call.getArguments().get("task_id");
        if (idArg == null) {
            return new ToolResult(false, "Error: 'task_id' argument is required");
        }
        String id = idArg.toString();

        String result = service.complete(id);
        boolean success = result.startsWith("Completed ");
        if (success) {
            // s23 P1c: 每行 unblocked / complete 走 log,不再打 console 彩色。
            for (String line : result.split("\n", -1)) {
                if (line.startsWith("Unblocked: ")) {
                    log.info("[TasksTool] unblocked: {}", line.substring(11));
                } else {
                    log.info("[TasksTool] complete: {}", line);
                }
            }
        }
        return new ToolResult(success, result);
    }

    /** 状态 → list_tasks 行首图标(跟 Python 一致)。 */
    private static String iconFor(TaskStatus status) {
        return switch (status) {
            case PENDING -> "○";
            case IN_PROGRESS -> "●";
            case COMPLETED -> "✓";
        };
    }
}
