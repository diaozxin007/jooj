package com.xilidou.marvis.harness.skill.impl;

import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.http.dto.InputSchema;
import com.xilidou.marvis.harness.skill.Skill;

import java.util.List;
import java.util.Map;

/**
 * FileSystemSkill - 文件系统操作（读/写/搜索）。
 *
 * <p>⚠️ 注意：当前实现是 mock 字符串，**不是真实文件操作**。
 * Week 4 实操时改为真实实现，参照 {@link BashSkill}。
 */
public class FileSystemSkill implements Skill {

    @Override
    public String getName() {
        return "filesystem";
    }

    @Override
    public String getDescription() {
        return "Read, write, and search files on the local filesystem.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(
                new ToolDefinition(
                        "read_file",
                        "Read a file with line numbers",
                        InputSchema.object(
                                Map.of(
                                        "path",   Map.of("type", "string", "description", "Absolute or relative file path"),
                                        "offset", Map.of("type", "integer", "description", "Line number to start reading from")
                                ),
                                "path"
                        )
                ),
                new ToolDefinition(
                        "write_file",
                        "Write content to a file",
                        InputSchema.object(
                                Map.of(
                                        "path",    Map.of("type", "string", "description", "Path to write to"),
                                        "content", Map.of("type", "string", "description", "Content to write")
                                ),
                                "path", "content"
                        )
                ),
                new ToolDefinition(
                        "search_files",
                        "Search file contents using regex",
                        InputSchema.object(
                                Map.of(
                                        "pattern", Map.of("type", "string", "description", "Regex pattern"),
                                        "path",    Map.of("type", "string", "description", "Directory to search in")
                                ),
                                "pattern"
                        )
                )
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if ("read_file".equals(call.getToolName())) {
            String path = (String) call.getArguments().get("path");
            return new ToolResult(true, String.format("[READ] %s: Line 1|import java... (50 lines shown)", path));
        }
        if ("write_file".equals(call.getToolName())) {
            String path = (String) call.getArguments().get("path");
            return new ToolResult(true, String.format("[WRITE] Saved to %s (1200 bytes)", path));
        }
        if ("search_files".equals(call.getToolName())) {
            String pattern = (String) call.getArguments().get("pattern");
            return new ToolResult(true, String.format("[SEARCH] Found 12 matches for '%s'", pattern));
        }
        return new ToolResult(false, "Unknown tool: " + call.getToolName());
    }
}
