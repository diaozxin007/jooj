package com.xilidou.marvis.harness.base;

import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.skill.Skill;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class SkillRegistry {

    private final Map<String, Skill> loadedSkills = new LinkedHashMap<>();
    private final Map<String, Skill> allTools = new LinkedHashMap<>(); // toolName -> Skill

    /**
     * 加载一个 Skill
     */
    public void load(Skill skill) {
        loadedSkills.put(skill.getName(), skill);
        // 注册该 Skill 下的所有工具
        for (ToolDefinition tool : skill.getTools()) {
            allTools.put(tool.getName(), skill);
        }
        log.info("Loaded skill: {} ({} tools)", skill.getName(), skill.getTools().size());
    }

    /**
     * 获取所有已加载的工具描述（用于 Prompt 中的 tools 参数）
     */
    public List<ToolDefinition> getAllTools() {
        List<ToolDefinition> result = new ArrayList<>();
        for (Skill skill : loadedSkills.values()) {
            result.addAll(skill.getTools());
        }
        return result;
    }

    /**
     * 执行一个工具调用
     */
    public ToolResult execute(ToolCall call) {
        Skill skill = allTools.get(call.getToolName());
        if (skill == null) {
            return new ToolResult(false,
                    String.format("Tool '%s' not found. Available tools: %s",
                            call.getToolName(), allTools.keySet()));
        }
        return skill.execute(call);
    }

    /**
     * 按需加载：只加载包含目标工具的 Skill
     * 实际场景中，LLM 返回工具调用后，可以动态加载对应 Skill
     */
    public void loadOnDemand(String toolName, Map<String, Skill> availableSkills) {
        for (Skill skill : availableSkills.values()) {
            for (ToolDefinition tool : skill.getTools()) {
                if (tool.getName().equals(toolName) && !loadedSkills.containsKey(skill.getName())) {
                    load(skill);
                    return;
                }
            }
        }
    }

    /**
     * 列出所有可用工具（不加载，仅描述）
     */
    public String getCapabilitiesSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available capabilities:\n");
        for (Skill skill : loadedSkills.values()) {
            sb.append(String.format("  - %s: %s\n", skill.getName(), skill.getDescription()));
            for (ToolDefinition tool : skill.getTools()) {
                sb.append(String.format("    - %s: %s\n", tool.getName(), tool.getDescription()));
            }
        }
        return sb.toString();
    }

}
