package com.xilidou.marvis.harness.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    @Test
    void empty_dir_initializes_with_zero_skills(@TempDir Path tmp) {
        SkillRegistry registry = new SkillRegistry(tmp);
        assertEquals(0, registry.size());
        assertEquals("", registry.catalog());
        assertTrue(registry.listNames().isEmpty());
    }

    @Test
    void nonexistent_dir_does_not_throw(@TempDir Path tmp) {
        Path doesNotExist = tmp.resolve("nope");
        // 应该静默通过，留 0 skill
        SkillRegistry registry = new SkillRegistry(doesNotExist);
        assertEquals(0, registry.size());
    }

    @Test
    void scans_yaml_frontmatter_correctly(@TempDir Path tmp) throws IOException {
        Path skillDir = tmp.resolve("greet");
        Files.createDirectory(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: greet
                description: Say hello in multiple languages
                ---

                # Greet skill body
                Hello / 你好 / Bonjour
                """);

        SkillRegistry registry = new SkillRegistry(tmp);
        assertEquals(1, registry.size());

        Optional<Skill> skill = registry.get("greet");
        assertTrue(skill.isPresent());
        assertEquals("greet", skill.get().getName());
        assertEquals("Say hello in multiple languages", skill.get().getDescription());
        assertTrue(skill.get().getBody().contains("Hello / 你好 / Bonjour"));
    }

    @Test
    void catalog_format_includes_name_and_description(@TempDir Path tmp) throws IOException {
        Path skillDir = tmp.resolve("a");
        Files.createDirectory(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: a
                description: First skill
                ---
                body
                """);

        SkillRegistry registry = new SkillRegistry(tmp);
        String catalog = registry.catalog();
        assertTrue(catalog.contains("**a**"));
        assertTrue(catalog.contains("First skill"));
    }

    @Test
    void missing_frontmatter_falls_back_to_dir_name(@TempDir Path tmp) throws IOException {
        Path skillDir = tmp.resolve("noFrontmatter");
        Files.createDirectory(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# A heading\nbody");

        SkillRegistry registry = new SkillRegistry(tmp);
        Optional<Skill> skill = registry.get("noFrontmatter");
        assertTrue(skill.isPresent(), "应该能加载没 frontmatter 的 SKILL.md（用目录名 fallback）");
    }

    @Test
    void malformed_yaml_does_not_kill_other_skills(@TempDir Path tmp) throws IOException {
        // skill 1：合法
        Path good = tmp.resolve("good");
        Files.createDirectory(good);
        Files.writeString(good.resolve("SKILL.md"), """
                ---
                name: good
                description: ok
                ---
                body
                """);

        // skill 2：YAML 错乱
        Path bad = tmp.resolve("bad");
        Files.createDirectory(bad);
        Files.writeString(bad.resolve("SKILL.md"), """
                ---
                name: bad
                description: : : malformed yaml :
                ---
                body
                """);

        SkillRegistry registry = new SkillRegistry(tmp);
        // good 应该被加载；bad 至少不应让 good 失败
        assertTrue(registry.get("good").isPresent(), "合法 skill 不应受 malformed skill 影响");
    }

    @Test
    void subdir_without_SKILL_md_is_skipped(@TempDir Path tmp) throws IOException {
        Path withSkill = tmp.resolve("with");
        Files.createDirectory(withSkill);
        Files.writeString(withSkill.resolve("SKILL.md"), """
                ---
                name: with
                description: has skill
                ---
                """);

        Path withoutSkill = tmp.resolve("without");
        Files.createDirectory(withoutSkill);
        Files.writeString(withoutSkill.resolve("README.md"), "no SKILL.md here");

        SkillRegistry registry = new SkillRegistry(tmp);
        assertEquals(1, registry.size(), "只应加载有 SKILL.md 的目录");
        assertTrue(registry.get("with").isPresent());
    }

    @Test
    void unknown_skill_name_returns_empty(@TempDir Path tmp) {
        SkillRegistry registry = new SkillRegistry(tmp);
        assertTrue(registry.get("nonexistent").isEmpty());
    }
}
