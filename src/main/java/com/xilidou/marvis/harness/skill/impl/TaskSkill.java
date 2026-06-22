package com.xilidou.marvis.harness.skill.impl;

import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.http.dto.InputSchema;
import com.xilidou.marvis.harness.skill.Skill;
import com.xilidou.marvis.harness.subagent.Subagent;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * TaskSkill - "task" 工具的实现，让父 Agent 派子 Agent 干活。
 *
 * <p>对应 Python s06 的：
 * <pre>
 *   TOOLS.append({"name": "task", "description": "Launch a subagent..."})
 *   TOOL_HANDLERS["task"] = spawn_subagent
 * </pre>
 *
 * <p>参数 {@code description}：要派给子 Agent 的任务描述。
 *
 * <p>返回：子 Agent 跑完后的最后一段 assistant 文本——所有中间过程对父透明。
 *
 * <h3>关键设计：循环依赖切断</h3>
 *
 * <p>TaskSkill 持有 Subagent 引用；Subagent 持有 SkillRegistry；SkillRegistry 持有 TaskSkill。
 * 这是一个**潜在的循环依赖**。Spring 启动时如果 TaskSkill 也 @Component，
 * 会被 SkillRegistry 注入 → SkillRegistry 又是 Subagent 的依赖 → ...
 *
 * <p>**当前不加 @Component**——TaskSkill 走 fromEnv 手工装配，避免循环。
 * Spring 化 Step 2/3 完成后再考虑用 @Lazy 或 setter 注入解决。
 */
@Slf4j
public class TaskSkill implements Skill {

    private final Subagent subagent;

    public TaskSkill(Subagent subagent) {
        this.subagent = subagent;
    }

    @Override
    public String getName() {
        return "task";
    }

    @Override
    public String getDescription() {
        return "Spawn a subagent to handle a complex subtask. " +
                "Returns only the final summary—the subagent's intermediate steps are hidden.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(new ToolDefinition(
                "task",
                "Launch a subagent to handle a complex subtask. " +
                        "Use this when a sub-problem would clutter your own context " +
                        "(e.g. reading 100 files to find one thing). " +
                        "Returns only the final conclusion.",
                InputSchema.object(
                        Map.of("description", Map.of(
                                "type", "string",
                                "description", "The full task description to delegate")),
                        "description"
                )
        ));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!"task".equals(call.getToolName())) {
            return new ToolResult(false, "Unknown tool: " + call.getToolName());
        }

        Object descArg = call.getArguments().get("description");
        if (descArg == null) {
            return new ToolResult(false, "Error: 'description' argument is required");
        }
        String description = descArg.toString();
        if (description.isBlank()) {
            return new ToolResult(false, "Error: 'description' must not be blank");
        }

        log.info("[Task] spawning subagent: {}",
                description.length() > 80 ? description.substring(0, 80) + "..." : description);

        String summary;
        try {
            summary = subagent.spawn(description);
        } catch (Exception e) {
            log.error("[Task] subagent failed", e);
            return new ToolResult(false, "Subagent failed: " + e.getMessage());
        }

        return new ToolResult(true, summary);
    }
}
