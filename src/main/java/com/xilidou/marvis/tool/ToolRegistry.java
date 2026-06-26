package com.xilidou.marvis.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool 注册表。
 *
 * <p>Spring 化设计:
 * <ul>
 *   <li>本类是 {@code @Component},由 Spring 容器管理生命周期</li>
 *   <li>构造器接收 {@code List<Tool>},Spring 自动注入所有标记 {@code @Component} 的 Tool 实现</li>
 *   <li>**加新 Tool 不需要改这个类**:新写一个 Tool 加 {@code @Component} 就行</li>
 * </ul>
 *
 * <p>非 Spring 场景(测试 / 独立 main):
 * <ul>
 *   <li>用无参构造器创建空 Registry,再手工 {@link #load(Tool)}</li>
 *   <li>或直接传 {@code List.of(skill1, skill2)} 给构造器</li>
 * </ul>
 *
 * <p>关键技术点:{@code @Autowired} 显式标注 Spring 应该用的构造器。
 * 没这个标注时,Spring 默认选**无参**构造器("最少参数"原则),
 * 会导致 Tool List 不被注入。
 *
 * <p>R4 重构(2026-06-24):从 {@code harness.base} 搬到 {@code harness.tool},
 * 与 Tool 接口和 ToolDefinition / ToolResult / ToolCall 同位置。
 * 原 {@code harness.base} 包就此消失。
 */
@Component
@Slf4j
public class ToolRegistry {

    private final Map<String, Tool> loadedTools = new LinkedHashMap<>();
    private final Map<String, Tool> allTools = new LinkedHashMap<>(); // toolName -> Tool

    /**
     * Spring 友好构造器:自动注入所有 {@code @Component} 标记的 Tool。
     *
     * <p>Spring 启动时会找到 {@link Tool} 的所有实现 Bean,按 Bean 顺序传进来。
     */
    @Autowired
    public ToolRegistry(List<Tool> tools) {
        if (tools != null) {
            tools.forEach(this::load);
        }
    }

    /**
     * 测试 / 独立 main 用:空 Registry,需要手工 {@link #load} 注册。
     */
    public ToolRegistry() {
        this(List.of());
    }

    /**
     * 加载一个 Tool
     */
    public void load(Tool tool) {
        loadedTools.put(tool.getName(), tool);
        // 注册该 Tool 下的所有工具
        for (ToolDefinition def : tool.getTools()) {
            allTools.put(def.getName(), tool);
        }
        log.info("Loaded tool: {} ({} entries)", tool.getName(), tool.getTools().size());
    }

    /**
     * 获取所有已加载的工具描述(用于 Prompt 中的 tools 参数)
     */
    public List<ToolDefinition> getAllTools() {
        List<ToolDefinition> result = new ArrayList<>();
        for (Tool skill : loadedTools.values()) {
            result.addAll(skill.getTools());
        }
        return result;
    }

    /**
     * 执行一个工具调用 —— 旧签名,等价于带 {@link ExecutionContext#lead()} 的调用。
     *
     * <p>保留这个签名是为了兼容现有调用点 / 测试。新调用点应该用
     * {@link #execute(ToolCall, ExecutionContext)} 显式传 ctx。
     */
    public ToolResult execute(ToolCall call) {
        return execute(call, ExecutionContext.lead());
    }

    /**
     * s18 新签名 —— 显式传 {@link ExecutionContext}。
     *
     * <p>调用方明确表达"谁在调 / 在哪调"语义,工具按 ctx 决定行为
     * (如 BashTool 在 worktree cwd 执行命令,FileSystemTool 用 cwd 解析相对路径)。
     */
    public ToolResult execute(ToolCall call, ExecutionContext ctx) {
        Tool skill = allTools.get(call.getToolName());
        if (skill == null) {
            return new ToolResult(false,
                    String.format("Tool '%s' not found. Available tools: %s",
                            call.getToolName(), allTools.keySet()));
        }
        return skill.execute(call, ctx);
    }

    /**
     * 按需加载:只加载包含目标工具的 Tool
     * 实际场景中,LLM 返回工具调用后,可以动态加载对应 Tool
     */
    public void loadOnDemand(String toolName, Map<String, Tool> availableTools) {
        for (Tool skill : availableTools.values()) {
            for (ToolDefinition tool : skill.getTools()) {
                if (tool.getName().equals(toolName) && !loadedTools.containsKey(skill.getName())) {
                    load(skill);
                    return;
                }
            }
        }
    }

    /**
     * 列出所有可用工具(不加载,仅描述)
     */
    public String getCapabilitiesSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available capabilities:\n");
        for (Tool skill : loadedTools.values()) {
            sb.append(String.format("  - %s: %s\n", skill.getName(), skill.getDescription()));
            for (ToolDefinition tool : skill.getTools()) {
                sb.append(String.format("    - %s: %s\n", tool.getName(), tool.getDescription()));
            }
        }
        return sb.toString();
    }

}
