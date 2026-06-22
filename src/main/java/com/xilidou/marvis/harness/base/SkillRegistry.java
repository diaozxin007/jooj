package com.xilidou.marvis.harness.base;

import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.skill.Skill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 注册表。
 *
 * <p>Spring 化设计：
 * <ul>
 *   <li>本类是 {@code @Component}，由 Spring 容器管理生命周期</li>
 *   <li>构造器接收 {@code List<Skill>}，Spring 自动注入所有标记 {@code @Component} 的 Skill 实现</li>
 *   <li>**加新 Skill 不需要改这个类**：新写一个 Skill 加 {@code @Component} 就行</li>
 * </ul>
 *
 * <p>非 Spring 场景（测试 / 独立 main）：
 * <ul>
 *   <li>用无参构造器创建空 Registry，再手工 {@link #load(Skill)}</li>
 *   <li>或直接传 {@code List.of(skill1, skill2)} 给构造器</li>
 * </ul>
 *
 * <p>关键技术点：{@code @Autowired} 显式标注 Spring 应该用的构造器。
 * 没这个标注时，Spring 默认选**无参**构造器（"最少参数"原则），
 * 会导致 Skill List 不被注入。
 */
@Component
@Slf4j
public class SkillRegistry {

    private final Map<String, Skill> loadedSkills = new LinkedHashMap<>();
    private final Map<String, Skill> allTools = new LinkedHashMap<>(); // toolName -> Skill

    /**
     * Spring 友好构造器：自动注入所有 {@code @Component} 标记的 Skill。
     *
     * <p>Spring 启动时会找到 {@link Skill} 的所有实现 Bean，按 Bean 顺序传进来。
     */
    @Autowired
    public SkillRegistry(List<Skill> skills) {
        if (skills != null) {
            skills.forEach(this::load);
        }
    }

    /**
     * 测试 / 独立 main 用：空 Registry，需要手工 {@link #load} 注册。
     */
    public SkillRegistry() {
        this(List.of());
    }

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
