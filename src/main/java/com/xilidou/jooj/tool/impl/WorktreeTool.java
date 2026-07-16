package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.team.WorktreeService;
import com.xilidou.jooj.tool.Tool;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WorktreeTool —— Lead 端的 3 个 worktree 工具,严格对齐上游 s18。
 *
 * <ol>
 *   <li>{@code create_worktree(name*, task_id?)} —— 新建 git worktree + 可选绑定 task</li>
 *   <li>{@code remove_worktree(name*, discard_changes?)} —— 删除 worktree(默认有改动拒绝)</li>
 *   <li>{@code keep_worktree(name*)} —— 留 worktree 给人工 review</li>
 * </ol>
 *
 * <p>跟其他 Lead 端工具(Cron / Team / Tasks)同模式 —— 一个 {@link Tool} 实现暴露多个
 * {@link ToolDefinition},switch 派发 {@link #execute}。
 *
 * <p><b>Subagent 白名单 / Teammate 白名单都不含本工具</b> —— worktree 创建/删除是 Lead 协调级
 * 操作,队友只**消费** worktree(claim 带 worktree 的 task 后切 cwd),不自己造 worktree。
 */
@Component
@Slf4j
public class WorktreeTool implements Tool {

    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";

    private final WorktreeService service;

    public WorktreeTool(WorktreeService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "worktree";
    }

    @Override
    public String getDescription() {
        return "Git worktree isolation for parallel teammate work (s18). " +
                "create_worktree / remove_worktree / keep_worktree.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        // create_worktree
        Map<String, Object> createSchema = new LinkedHashMap<>();
        createSchema.put("name", Map.of("type", "string",
                "description", "Worktree name; only [A-Za-z0-9._-], 1-64 chars."));
        createSchema.put("task_id", Map.of("type", "string",
                "description", "Optional task ID to bind to this worktree. " +
                        "Bound task's teammate will run tools in this worktree's cwd."));

        // remove_worktree
        Map<String, Object> removeSchema = new LinkedHashMap<>();
        removeSchema.put("name", Map.of("type", "string",
                "description", "Worktree name to remove."));
        removeSchema.put("discard_changes", Map.of("type", "boolean",
                "description",
                "Default false. If false and worktree has uncommitted changes, " +
                        "removal is refused — use keep_worktree to preserve, or set " +
                        "discard_changes=true to force removal."));

        // keep_worktree
        Map<String, Object> keepSchema = new LinkedHashMap<>();
        keepSchema.put("name", Map.of("type", "string",
                "description", "Worktree name to keep for manual review. " +
                        "Branch wt/<name> is preserved."));

        return List.of(
                new ToolDefinition(
                        "create_worktree",
                        "Create a git worktree at <worktree_dir>/<name> with branch wt/<name>. " +
                                "Optionally bind to a task — bound task's teammate will run " +
                                "tools (bash / read_file / write_file) in this worktree's cwd.",
                        InputSchema.object(createSchema, "name")),
                new ToolDefinition(
                        "remove_worktree",
                        "Remove a worktree (and its branch). Refuses if uncommitted changes " +
                                "exist unless discard_changes=true. Use keep_worktree to preserve.",
                        InputSchema.object(removeSchema, "name")),
                new ToolDefinition(
                        "keep_worktree",
                        "Mark a worktree as kept for manual review. Branch wt/<name> stays. " +
                                "Use this when finishing a task whose changes you want to merge later.",
                        InputSchema.object(keepSchema, "name"))
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            return switch (call.getToolName()) {
                case "create_worktree" -> doCreate(call);
                case "remove_worktree" -> doRemove(call);
                case "keep_worktree" -> doKeep(call);
                default -> new ToolResult(false, "Unknown tool: " + call.getToolName());
            };
        } catch (Exception e) {
            log.error("[Worktree] tool {} failed", call.getToolName(), e);
            return new ToolResult(false, "Error: " + e.getMessage());
        }
    }

    private ToolResult doCreate(ToolCall call) {
        Object nameArg = call.getArguments().get("name");
        if (nameArg == null) return new ToolResult(false, "Error: 'name' is required");
        String name = nameArg.toString();
        Object taskIdArg = call.getArguments().get("task_id");
        String taskId = taskIdArg != null ? taskIdArg.toString() : null;

        String result = service.create(name, taskId);
        boolean success = result.startsWith("Worktree '");
        if (success) {
            log.info("[WorktreeTool] {}", result);
        }
        return new ToolResult(success, result);
    }

    private ToolResult doRemove(ToolCall call) {
        Object nameArg = call.getArguments().get("name");
        if (nameArg == null) return new ToolResult(false, "Error: 'name' is required");
        Object discardArg = call.getArguments().get("discard_changes");
        boolean discard = discardArg instanceof Boolean b ? b
                : discardArg != null && Boolean.parseBoolean(discardArg.toString());

        String result = service.remove(nameArg.toString(), discard);
        boolean success = result.endsWith("removed");
        if (success) {
            log.info("[WorktreeTool] {}", result);
        }
        return new ToolResult(success, result);
    }

    private ToolResult doKeep(ToolCall call) {
        Object nameArg = call.getArguments().get("name");
        if (nameArg == null) return new ToolResult(false, "Error: 'name' is required");
        String result = service.keep(nameArg.toString());
        boolean success = !result.startsWith("Error:");
        if (success) {
            log.info("[WorktreeTool] {}", result);
        }
        return new ToolResult(success, result);
    }
}
