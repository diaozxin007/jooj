package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.skill.SkillRegistry;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillManageTool 单元测试 — Tier A skill 自创建闭环。
 * 不启动 Spring,纯 @TempDir 文件系统测试。
 */
class SkillManageToolTest {

    private SkillManageTool newTool(Path skillsDir) {
        SkillRegistry registry = new SkillRegistry(skillsDir);
        return new SkillManageTool(registry);
    }

    private static ToolCall call(String action, String name, String description, String body) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (action != null) args.put("action", action);
        if (name != null) args.put("name", name);
        if (description != null) args.put("description", description);
        if (body != null) args.put("body", body);
        return new ToolCall("skill_manage", args);
    }

    // ── create happy path ──

    @Test
    @DisplayName("create: 合法 skill 落盘 + 立刻进 catalog")
    void create_writes_and_registers(@TempDir Path tmp) throws IOException {
        SkillManageTool tool = newTool(tmp);

        ToolResult r = tool.execute(call("create", "git-rebase",
                "Rebase a branch interactively. Use when consolidating commits.",
                "# Git Rebase\n\nSteps:\n1. ...\n"));

        assertTrue(r.isSuccess(), "失败原因: " + r.getOutput());

        Path expected = tmp.resolve("git-rebase").resolve("SKILL.md");
        assertTrue(Files.exists(expected), "SKILL.md 应已写入");

        String content = Files.readString(expected);
        assertTrue(content.startsWith("---\n"));
        assertTrue(content.contains("name: git-rebase"));
        assertTrue(content.contains("description: Rebase"));
        assertTrue(content.contains("# Git Rebase"));
    }

    @Test
    @DisplayName("create 后立刻能 view")
    void create_then_view(@TempDir Path tmp) throws IOException {
        SkillManageTool tool = newTool(tmp);
        tool.execute(call("create", "x-skill", "X description sufficient.", "# X\nbody"));

        ToolResult viewed = tool.execute(call("view", "x-skill", null, null));
        assertTrue(viewed.isSuccess());
        assertTrue(viewed.getOutput().contains("# X"));
    }

    // ── 守门 ──

    @Test
    @DisplayName("create: 缺 description 拒")
    void create_rejects_missing_description(@TempDir Path tmp) {
        SkillManageTool tool = newTool(tmp);
        ToolResult r = tool.execute(call("create", "ok-name", null, "body"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("description"));
    }

    @Test
    @DisplayName("create: 缺 body 拒")
    void create_rejects_missing_body(@TempDir Path tmp) {
        SkillManageTool tool = newTool(tmp);
        ToolResult r = tool.execute(call("create", "ok-name", "good description.", null));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("body"));
    }

    @Test
    @DisplayName("create: name 含大写拒(spec)")
    void create_rejects_uppercase_name(@TempDir Path tmp) {
        SkillManageTool tool = newTool(tmp);
        ToolResult r = tool.execute(call("create", "BadName", "good description.", "body"));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().toLowerCase().contains("invalid skill name") ||
                   r.getOutput().toLowerCase().contains("name format"));
    }

    @Test
    @DisplayName("create: name 含连续连字符拒")
    void create_rejects_consecutive_hyphens(@TempDir Path tmp) {
        SkillManageTool tool = newTool(tmp);
        ToolResult r = tool.execute(call("create", "bad--name", "good description.", "body"));
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("create: 重名拒,提示 LLM 用 patch 或换名")
    void create_rejects_duplicate(@TempDir Path tmp) {
        SkillManageTool tool = newTool(tmp);
        tool.execute(call("create", "dup", "first description.", "first body"));

        ToolResult r2 = tool.execute(call("create", "dup", "second.", "second body"));
        assertFalse(r2.isSuccess());
        assertTrue(r2.getOutput().contains("already exists"));
    }

    @Test
    @DisplayName("create: 路径越权拒(name 含 .. 应在 validateName 阶段就拒)")
    void create_rejects_path_escape(@TempDir Path tmp) {
        SkillManageTool tool = newTool(tmp);
        // ".." 不符合 [a-z0-9-]+ 模式,validator 会拒
        ToolResult r = tool.execute(call("create", "..", "trying to escape.", "body"));
        assertFalse(r.isSuccess());
    }

    // ── view ──

    @Test
    @DisplayName("view: 不存在拒,列出可用 skill")
    void view_not_found(@TempDir Path tmp) {
        SkillManageTool tool = newTool(tmp);
        ToolResult r = tool.execute(call("view", "nonexistent", null, null));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().toLowerCase().contains("not found"));
    }

    // ── tool 元 ──

    @Test
    @DisplayName("tool 元数据:1 个 ToolDefinition,name=skill_manage")
    void exposes_one_tool_definition(@TempDir Path tmp) {
        SkillManageTool tool = newTool(tmp);
        var defs = tool.getTools();
        assertEquals(1, defs.size());
        assertEquals("skill_manage", defs.get(0).getName());
    }

    @Test
    @DisplayName("未知 action 拒")
    void rejects_unknown_action(@TempDir Path tmp) {
        SkillManageTool tool = newTool(tmp);
        ToolResult r = tool.execute(call("delete", "x", null, null));
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("Unknown action"));
    }

    @Test
    @DisplayName("create: description 含 YAML 特殊字符 → quote 包裹保 frontmatter 合法")
    void create_quotes_yaml_special_chars(@TempDir Path tmp) throws IOException {
        SkillManageTool tool = newTool(tmp);
        // 描述含 ": " 和 # —— 不 quote 会破坏 YAML
        ToolResult r = tool.execute(call("create", "yaml-test",
                "Use when key: value pairs need handling # and other YAML triggers.",
                "# YAML test\nbody"));
        assertTrue(r.isSuccess(), "应成功: " + r.getOutput());

        String content = Files.readString(tmp.resolve("yaml-test").resolve("SKILL.md"));
        // 验证写出的 YAML 合法 — description 被 quote 了
        assertTrue(content.contains("description: \""),
                "含 YAML 特殊字符的 description 应被 quote: " + content);
    }
}
