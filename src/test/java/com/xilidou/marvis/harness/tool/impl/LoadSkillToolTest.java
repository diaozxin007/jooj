package com.xilidou.marvis.harness.tool.impl;

import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoadSkillToolTest {

    @Test
    void loads_existing_skill(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("greet");
        Files.createDirectory(dir);
        String content = """
                ---
                name: greet
                description: Say hello
                ---
                # Greet body""";
        Files.writeString(dir.resolve("SKILL.md"), content);

        SkillRegistry registry = new SkillRegistry(tmp);
        LoadSkillTool tool = new LoadSkillTool(registry);

        ToolResult result = tool.execute(new ToolCall("load_skill", Map.of("name", "greet")));
        assertTrue(result.isSuccess());
        assertEquals(content, result.getOutput(), "应返回完整 SKILL.md 原文");
    }

    @Test
    void unknown_skill_returns_error(@TempDir Path tmp) {
        SkillRegistry registry = new SkillRegistry(tmp);
        LoadSkillTool tool = new LoadSkillTool(registry);

        ToolResult result = tool.execute(new ToolCall("load_skill", Map.of("name", "nope")));
        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("not found"));
    }

    @Test
    void missing_name_arg_returns_error(@TempDir Path tmp) {
        SkillRegistry registry = new SkillRegistry(tmp);
        LoadSkillTool tool = new LoadSkillTool(registry);

        ToolResult result = tool.execute(new ToolCall("load_skill", Map.of()));
        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("name"));
    }

    @Test
    void wrong_tool_name_returns_error(@TempDir Path tmp) {
        SkillRegistry registry = new SkillRegistry(tmp);
        LoadSkillTool tool = new LoadSkillTool(registry);

        ToolResult result = tool.execute(new ToolCall("not_load_skill", Map.of("name", "x")));
        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("Unknown tool"));
    }
}
