package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.mcp.McpServerRecord;
import com.xilidou.jooj.mcp.McpServerRegistry;
import com.xilidou.jooj.tool.Tool;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * McpManageTool —— LLM 动态管理 MCP server 的入口(M3,2026-07-14)。
 *
 * <p>跟 {@link SkillManageTool} 平行:LLM 完成任务时判断"需要 filesystem/git/postgres
 * MCP server" → 调 {@code mcp_manage(action=add, ...)} 自动装。add 成功后当前 turn
 * 结束,下一轮 {@code connect_mcp(name)} 就能激活其工具。
 *
 * <h3>actions</h3>
 *
 * <ul>
 *   <li>{@code add}    —— 落盘 {@code ~/.jooj/mcp-servers/<name>.json} + 加入 registry</li>
 *   <li>{@code list}   —— 列所有 server(name / status / enabled / lastError)</li>
 *   <li>{@code view}   —— 读单个 server 完整配置</li>
 *   <li>{@code remove} —— 从 registry + 磁盘一起删除</li>
 * </ul>
 *
 * <h3>守门</h3>
 *
 * <ol>
 *   <li>{@code action} / {@code name} 必填校验</li>
 *   <li>{@code add} 时 {@code command} / {@code args} 必填</li>
 *   <li>name 合法性校验委托 {@link com.xilidou.jooj.mcp.McpServersJsonStore}(禁 {@code /} {@code ..} 等)</li>
 *   <li>重名拒:{@code add} 检查 {@link McpServerRegistry#contains}</li>
 *   <li>{@link com.xilidou.jooj.permission.RuleBasedGate} 会拦 {@code add / remove} 走 ASK
 *       (list/view 直接放行)</li>
 * </ol>
 *
 * <p>本 tool 不做 test-connection —— 加了 server 之后如果连不上,下次 {@code connect_mcp}
 * 会把 status 标 FAILED + lastError,LLM 通过 {@code view} 或 UI 能看到。M4 会补 test。
 *
 * <p>不做 enable/disable —— {@link McpServerRegistry#setEnabled} 已就绪,但暴露给 LLM 的
 * 价值不高,留给 M4 UI toggle。
 */
@Component
@Slf4j
public class McpManageTool implements Tool {

    private final McpServerRegistry registry;

    public McpManageTool(McpServerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getName() {
        return "mcp_manage";
    }

    @Override
    public String getDescription() {
        return "Manage MCP servers (add / list / view / remove) at runtime.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", Map.of(
                "type", "string",
                "enum", List.of("add", "list", "view", "remove"),
                "description", "What to do."));
        props.put("name", Map.of(
                "type", "string",
                "description", "Server name (letters/digits/underscore/dash, no slashes). " +
                        "Required for add / view / remove."));
        props.put("command", Map.of(
                "type", "string",
                "description", "Executable command (e.g. 'npx', 'node', absolute path). Required for add."));
        props.put("args", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Command arguments list. Required for add (pass [] if none)."));
        props.put("env", Map.of(
                "type", "object",
                "additionalProperties", Map.of("type", "string"),
                "description", "Environment variables passed to the subprocess. Optional for add."));

        return List.of(new ToolDefinition(
                "mcp_manage",
                "Add / list / view / remove MCP servers at runtime. " +
                        "**When to call add:** user asks to install/setup an MCP server " +
                        "(e.g. \"add filesystem MCP\", \"connect git MCP with repo /path\"). " +
                        "After add succeeds, call connect_mcp(<name>) to activate its tools. " +
                        "**When to call remove:** user asks to uninstall / cleanup an MCP server. " +
                        "**When to call list/view:** to inspect current MCP state without changing anything.",
                InputSchema.object(props, "action")
        ));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!"mcp_manage".equals(call.getToolName())) {
            return new ToolResult(false, "Unknown tool: " + call.getToolName());
        }
        Map<String, Object> args = call.getArguments();
        Object actionArg = args.get("action");
        if (actionArg == null) return new ToolResult(false, "Error: 'action' is required (add / list / view / remove)");
        String action = actionArg.toString();

        return switch (action) {
            case "add" -> doAdd(args);
            case "list" -> doList();
            case "view" -> doView(args);
            case "remove" -> doRemove(args);
            default -> new ToolResult(false,
                    "Unknown action '" + action + "'. Expected: add / list / view / remove.");
        };
    }

    // ── actions ──────────────────────────────────────

    private ToolResult doAdd(Map<String, Object> args) {
        Object nameArg = args.get("name");
        Object commandArg = args.get("command");
        Object argsArg = args.get("args");
        if (nameArg == null) return new ToolResult(false, "Error: 'name' is required for add");
        if (commandArg == null) return new ToolResult(false, "Error: 'command' is required for add");
        if (argsArg == null) return new ToolResult(false, "Error: 'args' is required for add (pass [] if none)");

        String name = nameArg.toString();
        // 重名拒 —— 让 LLM 知道要先 remove 或换名字
        if (registry.contains(name)) {
            return new ToolResult(false,
                    "Server '" + name + "' already exists. Call action=view to inspect, " +
                            "or action=remove first then re-add.");
        }

        // args:LLM 传的可能是 List<String> 或含 non-string 元素;stringify 兜底
        List<String> cmdArgs = toStringList(argsArg);
        if (cmdArgs == null) {
            return new ToolResult(false,
                    "Error: 'args' must be an array of strings, got: " + argsArg.getClass().getSimpleName());
        }

        // env:可选,map 里可能有 non-string value,stringify 兜底
        Map<String, String> env = toStringMap(args.get("env"));
        if (env == null) {
            return new ToolResult(false,
                    "Error: 'env' must be an object with string values, got: " +
                            args.get("env").getClass().getSimpleName());
        }

        McpServerRecord record = new McpServerRecord(
                name, commandArg.toString(), cmdArgs, env, true,
                McpServerRecord.Status.NEVER_CONNECTED, null,
                Instant.now(), null);
        try {
            registry.add(record);
        } catch (IllegalArgumentException e) {
            // name 非法字符 / 已存在(理论上已被 contains 拦住,防御性)
            return new ToolResult(false, "Failed to add: " + e.getMessage());
        } catch (java.io.IOException e) {
            log.warn("[McpManage] add '{}' persist failed: {}", name, e.getMessage());
            return new ToolResult(false, "Failed to persist server: " + e.getMessage());
        }
        log.info("[McpManage] added server: {}", name);
        return new ToolResult(true,
                "Server '" + name + "' added. Call connect_mcp(server='" + name + "') to activate its tools.");
    }

    private ToolResult doList() {
        var all = registry.list();
        if (all.isEmpty()) return new ToolResult(true, "(no MCP servers registered)");
        String summary = all.stream().map(r -> {
            StringBuilder line = new StringBuilder();
            line.append("- ").append(r.name());
            line.append(" [").append(r.status()).append("]");
            if (!r.enabled()) line.append(" (disabled)");
            if (r.lastError() != null) line.append(" lastError=").append(r.lastError());
            return line.toString();
        }).collect(Collectors.joining("\n"));
        return new ToolResult(true, summary);
    }

    private ToolResult doView(Map<String, Object> args) {
        Object nameArg = args.get("name");
        if (nameArg == null) return new ToolResult(false, "Error: 'name' is required for view");
        return registry.get(nameArg.toString())
                .<ToolResult>map(r -> new ToolResult(true, formatRecord(r)))
                .orElseGet(() -> new ToolResult(false,
                        "Server not found: '" + nameArg + "'. Available: " + registry.listNames()));
    }

    private ToolResult doRemove(Map<String, Object> args) {
        Object nameArg = args.get("name");
        if (nameArg == null) return new ToolResult(false, "Error: 'name' is required for remove");
        String name = nameArg.toString();
        if (!registry.contains(name)) {
            return new ToolResult(false,
                    "Server not found: '" + name + "'. Available: " + registry.listNames());
        }
        try {
            registry.remove(name);
        } catch (java.io.IOException e) {
            log.warn("[McpManage] remove '{}' failed: {}", name, e.getMessage());
            return new ToolResult(false, "Failed to remove server: " + e.getMessage());
        }
        log.info("[McpManage] removed server: {}", name);
        return new ToolResult(true, "Server '" + name + "' removed.");
    }

    // ── helpers ─────────────────────────────────────

    /**
     * 把 JSON 反序列化后的 args 转成 {@code List<String>}。
     * LLM tool_use.input 里数组元素通常已是 String,但 stringify 兜底防止 int/bool。
     * 返回 null 表示 argsArg 不是 List(caller 报错)。
     */
    private static List<String> toStringList(Object argsArg) {
        if (!(argsArg instanceof List<?> list)) return null;
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) out.add(o == null ? "" : o.toString());
        return out;
    }

    /** 同上,针对 env map。返回 null 表示不是 Map。 */
    private static Map<String, String> toStringMap(Object envArg) {
        if (envArg == null) return Map.of();
        if (!(envArg instanceof Map<?, ?> map)) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() == null) continue;
            out.put(e.getKey().toString(),
                    e.getValue() == null ? "" : e.getValue().toString());
        }
        return out;
    }

    private static String formatRecord(McpServerRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(r.name()).append('\n');
        sb.append("command: ").append(r.command()).append('\n');
        sb.append("args: ").append(r.args()).append('\n');
        sb.append("env: ").append(r.env()).append('\n');
        sb.append("enabled: ").append(r.enabled()).append('\n');
        sb.append("status: ").append(r.status()).append('\n');
        if (r.lastError() != null) sb.append("lastError: ").append(r.lastError()).append('\n');
        sb.append("addedAt: ").append(r.addedAt()).append('\n');
        if (r.lastConnectedAt() != null) sb.append("lastConnectedAt: ").append(r.lastConnectedAt()).append('\n');
        return sb.toString();
    }
}
