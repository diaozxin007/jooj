package com.xilidou.marvis.tool.impl;

import com.xilidou.marvis.cron.CronJob;
import com.xilidou.marvis.cron.CronService;
import com.xilidou.marvis.http.dto.InputSchema;
import com.xilidou.marvis.tool.Tool;
import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.tool.ToolDefinition;
import com.xilidou.marvis.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CronTool —— s14 Cron Scheduler 的 3 个工具,严格对齐上游
 * [s14_cron_scheduler/code.py] 的 {@code TOOLS} 数组。
 *
 * <ol>
 *   <li>{@code schedule_cron(cron*, prompt*, recurring?, durable?)}</li>
 *   <li>{@code list_crons()} 无参</li>
 *   <li>{@code cancel_cron(job_id*)}</li>
 * </ol>
 *
 * <p>跟 {@link TasksTool} 同模式 —— 一个 {@link Tool} 实现暴露多个 {@link ToolDefinition},
 * 用 switch 分派 {@link #execute}。
 *
 * <h3>错误是字符串,不抛异常</h3>
 *
 * <p>{@link CronService#schedule} / {@link CronService#cancel} 失败时返回**人类可读字符串**
 * (NL 错误如 {@code "Error: cron expression must have exactly 5 fields"})。
 * 本工具把它们包成 {@link ToolResult#isSuccess()}=false 的 {@link ToolResult},
 * LLM 看到 string 自我纠正。
 *
 * <h3>不依赖 Subagent / Recovery / Permission</h3>
 *
 * <p>本类只依赖 {@link CronService},独立层。
 */
@Component
@Slf4j
public class CronTool implements Tool {

    private static final String CYAN = "\033[36m";
    private static final String YELLOW = "\033[33m";
    private static final String RESET = "\033[0m";

    private final CronService service;

    public CronTool(CronService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "cron";
    }

    @Override
    public String getDescription() {
        return "5-field cron scheduler. schedule_cron / list_crons / cancel_cron " +
                "to trigger prompts on schedule (s14).";
    }

    @Override
    public List<ToolDefinition> getTools() {
        // schedule_cron schema —— cron / prompt 必填,recurring / durable 可选
        Map<String, Object> scheduleSchema = Map.of(
                "cron", Map.of(
                        "type", "string",
                        "description",
                        "5-field cron expression: minute hour day_of_month month day_of_week. " +
                                "Examples: '0 9 * * *' (daily 9am), '*/5 * * * *' (every 5min), " +
                                "'0 9 * * 1-5' (weekday 9am)."),
                "prompt", Map.of(
                        "type", "string",
                        "description", "Prompt text injected into agent_loop when fired."),
                "recurring", Map.of(
                        "type", "boolean",
                        "description", "If false, fires once then auto-removes. Default: true."),
                "durable", Map.of(
                        "type", "boolean",
                        "description",
                        "If true, persisted to .scheduled_tasks.json and restored on restart. " +
                                "Default: false.")
        );

        // cancel_cron schema —— 单 job_id
        Map<String, Object> cancelSchema = Map.of(
                "job_id", Map.of(
                        "type", "string",
                        "description", "Cron job ID, e.g. cron_123456")
        );

        return List.of(
                new ToolDefinition(
                        "schedule_cron",
                        "Schedule a recurring or one-shot prompt with a 5-field cron expression.",
                        InputSchema.object(scheduleSchema, "cron", "prompt")),
                new ToolDefinition(
                        "list_crons",
                        "List all scheduled cron jobs with id, expression, prompt, and flags.",
                        InputSchema.object(Map.of())),
                new ToolDefinition(
                        "cancel_cron",
                        "Cancel a scheduled cron job by ID.",
                        InputSchema.object(cancelSchema, "job_id"))
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            return switch (call.getToolName()) {
                case "schedule_cron" -> doSchedule(call);
                case "list_crons" -> doList();
                case "cancel_cron" -> doCancel(call);
                default -> new ToolResult(false, "Unknown tool: " + call.getToolName());
            };
        } catch (IllegalArgumentException e) {
            return new ToolResult(false, "Error: " + e.getMessage());
        } catch (Exception e) {
            log.error("[Cron] tool {} failed", call.getToolName(), e);
            return new ToolResult(false, "Error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Handlers
    // ─────────────────────────────────────────────────────────────

    private ToolResult doSchedule(ToolCall call) {
        Object cronArg = call.getArguments().get("cron");
        Object promptArg = call.getArguments().get("prompt");
        if (cronArg == null) return new ToolResult(false, "Error: 'cron' argument is required");
        if (promptArg == null) return new ToolResult(false, "Error: 'prompt' argument is required");

        boolean recurring = parseBoolean(call.getArguments().get("recurring"), true);
        boolean durable = parseBoolean(call.getArguments().get("durable"), false);

        String result = service.schedule(
                cronArg.toString(), promptArg.toString(), recurring, durable);
        if (result.startsWith("Error:")) {
            return new ToolResult(false, result);
        }
        // service 返回的是新 id;包成完整描述给 LLM 看
        String msg = String.format("Scheduled %s: '%s' → %s",
                result, cronArg, promptArg);
        System.out.println("  " + CYAN + "[cron schedule] " + msg + RESET);
        return new ToolResult(true, msg);
    }

    private ToolResult doList() {
        List<CronJob> jobs = service.list();
        if (jobs.isEmpty()) {
            return new ToolResult(true, "No scheduled cron jobs. Use schedule_cron to add one.");
        }
        StringBuilder sb = new StringBuilder();
        for (CronJob j : jobs) {
            // 格式:  ⏰ cron_123456: '0 9 * * *' → 'do X' [recurring=true, durable=false]
            sb.append("  ⏰ ").append(j.getId())
                    .append(": '").append(j.getCron()).append("' → ")
                    .append("'").append(j.getPrompt()).append("' ")
                    .append("[recurring=").append(j.isRecurring())
                    .append(", durable=").append(j.isDurable()).append("]\n");
        }
        String out = sb.toString();
        if (out.endsWith("\n")) out = out.substring(0, out.length() - 1);
        return new ToolResult(true, out);
    }

    private ToolResult doCancel(ToolCall call) {
        Object idArg = call.getArguments().get("job_id");
        if (idArg == null) {
            return new ToolResult(false, "Error: 'job_id' argument is required");
        }
        String id = idArg.toString();
        String result = service.cancel(id);
        boolean success = result.startsWith("Cancelled ");
        if (success) {
            System.out.println("  " + YELLOW + "[cron cancel] " + result + RESET);
        }
        return new ToolResult(success, result);
    }

    private static boolean parseBoolean(Object value, boolean defaultVal) {
        if (value == null) return defaultVal;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return defaultVal;
    }
}
