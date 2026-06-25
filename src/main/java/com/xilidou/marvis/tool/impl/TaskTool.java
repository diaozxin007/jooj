package com.xilidou.marvis.tool.impl;

import com.xilidou.marvis.http.dto.InputSchema;
import com.xilidou.marvis.subagent.Subagent;
import com.xilidou.marvis.tool.Tool;
import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.tool.ToolDefinition;
import com.xilidou.marvis.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * TaskTool —— 让 LLM 派子 Agent(s06)的能力,正常 {@link Tool} 实现。
 *
 * <p>对应 Python s06 的 {@code task} 工具:LLM 收到一个适合做"侧线"的子任务时,
 * 调 {@code task(description=...)},marvis 用 fresh context 跑一个 {@link Subagent}
 * 完成这个任务,只把最终摘要回填给 LLM,中间过程对父 Agent 不可见。
 *
 * <h3>历史:R4 的"结构性消除"为什么改回 @Lazy</h3>
 *
 * <p>R4 重构(2026-06-24)曾把 {@code task} 工具的定义和分发逻辑内联进
 * {@code AgentLoopHarness},理由是消除三方循环依赖
 * {@code TaskTool → Subagent → ToolRegistry → List<Tool> ⊃ TaskTool}。
 * 那是审美选择,不是技术必需 —— Spring 的 {@code @Lazy} 完全能打破这个循环。
 *
 * <p>s12 上线后,如果继续保留 {@code task} 内联,5 个 {@code create_task / list_tasks /
 * get_task / claim_task / complete_task} 走标准 {@link com.xilidou.marvis.tool.ToolRegistry}
 * 路径,而单独 {@code task} 走 {@link com.xilidou.marvis.agent.AgentLoopHarness}
 * 内置分发,语义不一致。重新把 {@code task} 抽回 {@code Tool} 实现,所有任务相关工具
 * 走同一条路径,代码反而更整齐。
 *
 * <h3>@Lazy 打破循环</h3>
 *
 * <pre>
 *   TaskTool ──(@Lazy)──▶ Subagent ─▶ ToolRegistry ─▶ List&lt;Tool&gt; ⊃ TaskTool
 *            ↑ Spring 注入的是代理,实际调用时才 resolve
 * </pre>
 *
 * <p>Spring 看到 {@code @Lazy} 标记,会注入一个 Subagent 代理(JDK 动态代理或
 * CGLIB 子类),容器装配阶段不实例化真正的 Subagent,循环也就不成立。
 * 第一次调用 {@code subagent.spawn(...)} 时代理才回头去 BeanFactory 拿真身。
 *
 * <p>对比"结构性消除"路线:@Lazy 不需要把任何 bean 从 List 里拿出来,加新工具不用
 * 改 AgentLoopHarness,扩展开闭原则更纯。
 */
@Component
@Slf4j
public class TaskTool implements Tool {

    private final Subagent subagent;

    /**
     * @Lazy 打破循环依赖:
     * {@code TaskTool → Subagent → ToolRegistry → List<Tool> ⊃ TaskTool}。
     *
     * <p>标记 @Lazy 后 Spring 注入的是 Subagent 代理,实际调用时才 resolve,
     * 容器完成装配阶段不形成实例化循环。
     */
    @Autowired
    public TaskTool(@Lazy Subagent subagent) {
        this.subagent = subagent;
    }

    @Override
    public String getName() {
        return "task";
    }

    @Override
    public String getDescription() {
        return "Launch a subagent to handle a complex subtask.";
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
        try {
            return new ToolResult(true, subagent.spawn(description));
        } catch (Exception e) {
            log.error("[Task] subagent failed", e);
            return new ToolResult(false, "Subagent failed: " + e.getMessage());
        }
    }
}
