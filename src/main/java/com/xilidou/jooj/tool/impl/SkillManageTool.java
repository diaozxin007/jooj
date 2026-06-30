package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.skill.Skill;
import com.xilidou.jooj.skill.SkillFrontmatterValidator;
import com.xilidou.jooj.skill.SkillRegistry;
import com.xilidou.jooj.tool.Tool;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SkillManageTool —— LLM 自学习闭环的入口(s21 Demo 18,Tier A:Hermes 类自创建)。
 *
 * <p>跟 {@link LoadSkillTool} 的边界:
 * <ul>
 *   <li>{@code LoadSkillTool}(load_skill)— **只读**,LLM 调它把 SKILL.md body 拉进对话</li>
 *   <li>{@code SkillManageTool}(skill_manage)— **可写**,LLM 完成复杂任务后**主动**保存经验为 skill</li>
 * </ul>
 *
 * <h3>触发场景(SYSTEM prompt 引导,见 {@link com.xilidou.jooj.JoojProperties.Prompt})</h3>
 *
 * <p>跟 Hermes 同款判断条件:
 * <ol>
 *   <li>完成复杂任务(5+ tool calls)成功</li>
 *   <li>撞错误绕过去找到通路</li>
 *   <li>用户纠正方法后</li>
 *   <li>发现非平凡的 workflow</li>
 * </ol>
 *
 * <h3>actions</h3>
 *
 * <ul>
 *   <li>{@code create} — 创建新 skill,落 {@code skills/<name>/SKILL.md}</li>
 *   <li>{@code view} — 读已有 skill 完整内容(供 LLM 决定是否 patch — Tier B 后续)</li>
 * </ul>
 *
 * <h3>守门</h3>
 *
 * <ol>
 *   <li>frontmatter 校验:复用 {@link SkillFrontmatterValidator}(name 格式 + description 长度)</li>
 *   <li>路径校验:resolve 后必须落在 {@code skillsDir} 内,防 {@code ../} 越权</li>
 *   <li>不允许覆盖已存在 skill:重名直接拒,提示 LLM 用 {@code patch}(未来)或换名</li>
 *   <li>create 成功后立刻 {@code rescan(force=true)},新 skill 当前 turn 之后就在 catalog 里</li>
 * </ol>
 *
 * <p>不实现 Hermes 的 {@code write_approval} gate(开发场景信任 LLM)+ {@code Curator}
 * 后台清理(留给 Tier D)。
 */
@Component
@Slf4j
public class SkillManageTool implements Tool {

    private final SkillRegistry skillRegistry;

    public SkillManageTool(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public String getName() {
        return "skill_manage";
    }

    @Override
    public String getDescription() {
        return "Manage agent skills (self-improvement loop): create new skills from successful task patterns.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        Map<String, Object> createProps = new LinkedHashMap<>();
        createProps.put("action", Map.of(
                "type", "string",
                "enum", List.of("create", "view"),
                "description", "What to do: create a new skill, or view full content of an existing one"));
        createProps.put("name", Map.of(
                "type", "string",
                "description", "Skill name (lowercase letters/digits/hyphens, ≤64 chars, equals dir name). " +
                        "Required for both create and view."));
        createProps.put("description", Map.of(
                "type", "string",
                "description", "Short description (≤1024 chars) — what the skill does AND when to use it. " +
                        "Required for create. Will be in the SYSTEM catalog so make it triggering keywords."));
        createProps.put("body", Map.of(
                "type", "string",
                "description", "Full SKILL.md body content (markdown after frontmatter). " +
                        "Required for create. Include step-by-step instructions, examples, edge cases."));

        return List.of(new ToolDefinition(
                "skill_manage",
                "Create or view a skill in the project skills/ directory. " +
                        "**When to call create:** after completing a complex task (5+ tool calls), " +
                        "after recovering from errors, after user corrected your approach, " +
                        "or when you discovered a non-trivial workflow worth reusing. " +
                        "The skill becomes immediately available in the catalog after creation.",
                InputSchema.object(createProps, "action", "name")
        ));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!"skill_manage".equals(call.getToolName())) {
            return new ToolResult(false, "Unknown tool: " + call.getToolName());
        }

        Object actionArg = call.getArguments().get("action");
        Object nameArg = call.getArguments().get("name");
        if (actionArg == null) return new ToolResult(false, "Error: 'action' is required (create / view)");
        if (nameArg == null) return new ToolResult(false, "Error: 'name' is required");

        String action = actionArg.toString();
        String name = nameArg.toString();

        return switch (action) {
            case "create" -> doCreate(name, call.getArguments());
            case "view" -> doView(name);
            default -> new ToolResult(false, "Unknown action '" + action + "'. Expected: create / view");
        };
    }

    // ── actions ──

    private ToolResult doCreate(String name, Map<String, Object> args) {
        Object descArg = args.get("description");
        Object bodyArg = args.get("body");
        if (descArg == null) return new ToolResult(false, "Error: 'description' is required for create");
        if (bodyArg == null) return new ToolResult(false, "Error: 'body' is required for create");

        String description = descArg.toString();
        String body = bodyArg.toString();

        // 守门 1:name 格式合规(spec 要求 == 父目录名,这里 name 即将作为目录名)
        String err = SkillFrontmatterValidator.validateName(name, name);
        if (err != null) return new ToolResult(false, "Invalid skill name: " + err);
        if ((err = SkillFrontmatterValidator.validateDescription(description)) != null) {
            return new ToolResult(false, "Invalid description: " + err);
        }

        // 守门 2:路径越权(虽然 name 已经过 validator 不可能含 .. 或 /,加防御)
        Path skillsDir = skillRegistry.getSkillsDir();
        Path skillDir = skillsDir.resolve(name).normalize();
        if (!skillDir.startsWith(skillsDir.normalize())) {
            return new ToolResult(false, "Path escape detected, refusing to create '" + name + "'");
        }

        // 守门 3:重名拒
        if (skillRegistry.get(name).isPresent()) {
            return new ToolResult(false, "Skill '" + name + "' already exists. " +
                    "Use a different name or wait for skill_manage(action=patch) support (Tier B).");
        }
        if (Files.exists(skillDir)) {
            return new ToolResult(false, "Directory '" + skillDir + "' already exists but skill not in registry. " +
                    "Manual cleanup needed before creating with this name.");
        }

        // 组装 SKILL.md
        String skillMd = buildSkillMd(name, description, body);

        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), skillMd, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[SkillManage] failed to write {}: {}", skillDir, e.getMessage());
            return new ToolResult(false, "Failed to write SKILL.md: " + e.getMessage());
        }

        // 立刻 rescan,新 skill 当前 turn 后即在 catalog 中(SystemPromptAssembler 下一轮重读)
        int total = skillRegistry.rescan(true);
        log.info("[SkillManage] created skill: {} (registry now has {} skills)", name, total);

        return new ToolResult(true, "Skill '" + name + "' created at " + skillDir +
                ". It is now in the catalog and will be visible in your next turn.");
    }

    private ToolResult doView(String name) {
        return skillRegistry.get(name)
                .<ToolResult>map(s -> new ToolResult(true, s.getBody()))
                .orElseGet(() -> new ToolResult(false,
                        "Skill not found: '" + name + "'. Available: " + skillRegistry.listNames()));
    }

    /**
     * 组装 SKILL.md —— frontmatter(name + description)+ 空行 + body。
     * 不写可选字段(license/compatibility/metadata/allowed-tools)是因为 LLM 自创建时
     * 这些信息无从可信地推断,留空让 spec 兼容(它们都是 optional)。
     */
    private static String buildSkillMd(String name, String description, String body) {
        // 转义:description 在 YAML 里如果含 : 或换行,要包裹引号才安全
        String safeDesc = needsYamlQuote(description)
                ? "\"" + description.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                : description;

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append('\n');
        sb.append("description: ").append(safeDesc).append('\n');
        sb.append("---\n\n");
        // body 不强制 trim,保留 LLM 给的原样
        sb.append(body);
        if (!body.endsWith("\n")) sb.append('\n');
        return sb.toString();
    }

    /** YAML 里"flow scalar" 包含特殊字符必须 quote。简化判断:含 : 或 # 或换行就 quote。 */
    private static boolean needsYamlQuote(String s) {
        if (s == null) return false;
        return s.contains(": ") || s.contains("#") || s.contains("\n") || s.contains("\"");
    }
}
