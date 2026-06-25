package com.xilidou.marvis.tool.impl;

import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.tool.ToolDefinition;
import com.xilidou.marvis.tool.ToolResult;
import com.xilidou.marvis.http.dto.InputSchema;
import com.xilidou.marvis.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * BashTool - 对应 Python s01 中的 run_bash 工具
 * 执行 shell 命令并返回输出，含危险命令拦截
 */
@Component
public class BashTool implements Tool {

    private static final List<String> DANGEROUS = Arrays.asList(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
    );

    private static final int MAX_OUTPUT = 50000;
    private static final int TIMEOUT_SECONDS = 120;

    @Override
    public String getName() {
        return "bash";
    }

    @Override
    public String getDescription() {
        return "Run a shell command in the current working directory.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        // s13: bash 工具加可选 run_in_background boolean 参数。
        // 这是"语义提示给 marvis"——AgentLoopHarness 在派发前提前读出来,
        // 不传到 executeBash;BashTool 自身不感知此参数。
        // 跟上游 [s13_background_tasks/code.py] 的 RUN_BASH 工具 schema 严格一致。
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("command", Map.of(
                "type", "string",
                "description", "The shell command to run"));
        properties.put("run_in_background", Map.of(
                "type", "boolean",
                "description",
                "Optional. If true, marvis runs this in a daemon thread, returns " +
                        "a placeholder bg_id immediately, and injects the result as a " +
                        "<task_notification> in the next turn. Use for slow ops " +
                        "(builds / tests / installs / docker / make)."));

        return List.of(
                new ToolDefinition(
                        "bash",
                        "Run a shell command.",
                        InputSchema.object(properties, "command")
                )
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!"bash".equals(call.getToolName())) {
            return new ToolResult(false, "Unknown tool: " + call.getToolName());
        }

        String command = (String) call.getArguments().get("command");
        if (command == null || command.isBlank()) {
            return new ToolResult(false, "Error: command is required");
        }

        // 安全拦截
        for (String danger : DANGEROUS) {
            if (command.contains(danger)) {
                return new ToolResult(false, "Error: Dangerous command blocked");
            }
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File(System.getProperty("user.dir")));

            Process process = pb.start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new ToolResult(false, "Error: Timeout (" + TIMEOUT_SECONDS + "s)");
            }

            String output = new String(process.getInputStream().readAllBytes()).strip();
            if (output.isEmpty()) {
                output = "(no output)";
            }
            if (output.length() > MAX_OUTPUT) {
                output = output.substring(0, MAX_OUTPUT);
            }
            return new ToolResult(true, output);

        } catch (Exception e) {
            return new ToolResult(false, "Error: " + e.getMessage());
        }
    }
}