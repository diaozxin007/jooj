package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.skill.Skill;
import com.xilidou.jooj.skill.SkillRegistry;
import com.xilidou.jooj.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LoadSkillTool - 让 LLM 按需加载 Skill 完整内容（s07 核心工具）。
 *
 * <p>对应 Python s07 的 {@code load_skill(name)} 工具。
 *
 * <h3>使用模式</h3>
 *
 * <pre>
 *   1. 启动时：SYSTEM prompt 含 catalog（"Skills available: code-review, agent-builder..."）
 *   2. LLM 看到任务匹配某 skill → 调 load_skill("code-review")
 *   3. 本工具返回完整 SKILL.md body 作为 tool_result
 *   4. body 进入 messages 历史，LLM 把它当作"临时 SYSTEM prompt"按手册指引干活
 * </pre>
 *
 * <h3>为什么是工具不是直接注入</h3>
 *
 * <p>Skill body 通常 1000-3000 token——如果全塞 SYSTEM 太贵；按需加载只在用到时才付费。
 * 这就是 s07 的"两层加载"原理：
 * <ul>
 *   <li>Layer 1: catalog（每个 skill ~100 token）— 永远在 SYSTEM</li>
 *   <li>Layer 2: body（每个 skill ~2000 token）— 按需通过本工具加载</li>
 * </ul>
 */
@Component
@Slf4j
public class LoadSkillTool implements Tool {

    private final SkillRegistry skillRegistry;

    public LoadSkillTool(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public String getName() {
        return "skill";   // Tool 命名空间，下含 load_skill 这一个工具
    }

    @Override
    public String getDescription() {
        return "Load full content of a skill by name.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(new ToolDefinition(
                "load_skill",
                "Load the full content of a skill by name. Use this when the catalog " +
                        "in your SYSTEM prompt indicates a skill is relevant to the current task.",
                InputSchema.object(
                        Map.of("name", Map.of(
                                "type", "string",
                                "description", "The skill name (from the catalog in SYSTEM prompt)")),
                        "name"
                )
        ));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!"load_skill".equals(call.getToolName())) {
            return new ToolResult(false, "Unknown tool: " + call.getToolName());
        }

        Object nameArg = call.getArguments().get("name");
        if (nameArg == null) {
            return new ToolResult(false, "Error: 'name' argument is required");
        }
        String name = nameArg.toString();

        Optional<Skill> skill = skillRegistry.get(name);
        if (skill.isEmpty()) {
            return new ToolResult(false,
                    "Skill not found: '" + name + "'. Available: " + skillRegistry.listNames());
        }

        log.info("[Skill] loaded: {} ({} chars)", name, skill.get().getBody().length());
        return new ToolResult(true, skill.get().getBody());
    }
}
