package com.xilidou.marvis.harness.skill.impl;

import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.http.dto.InputSchema;
import com.xilidou.marvis.harness.skill.Skill;

import java.util.List;
import java.util.Map;

/**
 * TerminalSkill - 通用 shell 执行。
 *
 * <p>⚠️ 注意：和 {@link BashSkill} 功能重叠，但 BashSkill 是真实实现。
 * 这个类的 execute 是 mock。Week 4 决策：保留 BashSkill，删除或重写这个。
 */
public class TerminalSkill implements Skill {

    @Override
    public String getName() {
        return "terminal";
    }

    @Override
    public String getDescription() {
        return "Execute shell commands on the system.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(
                new ToolDefinition(
                        "terminal",
                        "Run a shell command",
                        InputSchema.object(
                                Map.of(
                                        "command", Map.of("type", "string",  "description", "The shell command to run"),
                                        "timeout", Map.of("type", "integer", "description", "Timeout in seconds, default 180")
                                ),
                                "command"
                        )
                )
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if ("terminal".equals(call.getToolName())) {
            String command = (String) call.getArguments().get("command");
            return new ToolResult(true,
                    String.format("[TERMINAL] Executed '%s' -> exit code 0, output: 'done'", command));
        }
        return new ToolResult(false, "Unknown tool: " + call.getToolName());
    }
}
