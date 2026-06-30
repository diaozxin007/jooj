package com.xilidou.jooj.skill;

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
    @org.junit.jupiter.api.DisplayName("Demo 17 后:没 frontmatter 但内容存在 → 仍能用 fallback 加载(name 合规时)")
    void missing_frontmatter_falls_back_when_dir_legal(@TempDir Path tmp) throws IOException {
        // jooj 的 fallback 策略:name 用目录名,description 用 first-non-empty-line。
        // Demo 17 后仍允许这种 fallback —— 只要 fallback 出的值通过 validator。
        // 这跟 spec 严格"必须显式声明"有偏差,但兼容老 SKILL.md。
        Path skillDir = tmp.resolve("noframtter");
        Files.createDirectory(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# A heading\nbody");

        SkillRegistry registry = new SkillRegistry(tmp);
        Optional<Skill> skill = registry.get("noframtter");
        assertTrue(skill.isPresent(), "fallback 出的 name + description 都合法时,应能加载");
        assertEquals("A heading", skill.get().getDescription());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Demo 17:目录名含大写时 fallback 不合规,被拒")
    void missing_frontmatter_rejected_when_dir_uppercase(@TempDir Path tmp) throws IOException {
        // 目录名 noFrontmatter(含大写)— fallback 出的 name 不符合 spec(必须全小写),拒载
        Path skillDir = tmp.resolve("noFrontmatter");
        Files.createDirectory(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# A heading\nbody");

        SkillRegistry registry = new SkillRegistry(tmp);
        assertTrue(registry.get("noFrontmatter").isEmpty(),
                "fallback 出的 name='noFrontmatter' 含大写,validator 拒绝");
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

    // ── s21 Demo 17:agentskills.io spec 对齐 ──

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Demo 17: 读取全部 6 个 frontmatter 字段")
    void reads_all_spec_fields(@TempDir Path tmp) throws IOException {
        Path skillDir = tmp.resolve("pdf-processing");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: pdf-processing
                description: Extract PDF text and tables. Use for PDFs.
                license: Apache-2.0
                compatibility: Requires Python 3.14+
                metadata:
                  author: example-org
                  version: "1.0"
                allowed-tools: Bash(git:*) Read
                ---
                # PDF Processing
                """);

        SkillRegistry registry = new SkillRegistry(tmp);
        Skill s = registry.get("pdf-processing").orElseThrow();
        assertEquals("pdf-processing", s.getName());
        assertTrue(s.getDescription().contains("Extract PDF"));
        assertEquals("Apache-2.0", s.getLicense());
        assertEquals("Requires Python 3.14+", s.getCompatibility());
        assertEquals("Bash(git:*) Read", s.getAllowedTools());
        assertNotNull(s.getMetadata());
        assertEquals("example-org", s.getMetadata().get("author"));
        assertEquals("1.0", s.getMetadata().get("version"));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Demo 17: 老 SKILL.md 只有 name+description 仍能加载")
    void minimal_skill_still_loads(@TempDir Path tmp) throws IOException {
        Path skillDir = tmp.resolve("hello");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: hello
                description: A simple hello skill.
                ---
                # Hello
                """);

        SkillRegistry registry = new SkillRegistry(tmp);
        Skill s = registry.get("hello").orElseThrow();
        assertEquals("hello", s.getName());
        assertNull(s.getLicense());
        assertNull(s.getCompatibility());
        assertNull(s.getAllowedTools());
        assertNull(s.getMetadata());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Demo 17: name 不匹配父目录名 → 该 skill 被拒绝加载")
    void rejects_name_mismatch(@TempDir Path tmp) throws IOException {
        Path skillDir = tmp.resolve("good");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: bad
                description: name doesn't match dir.
                ---
                """);

        SkillRegistry registry = new SkillRegistry(tmp);
        // 不合规 → scanDir 的 try-catch 静默 skip,registry 应为空
        assertEquals(0, registry.size());
        assertTrue(registry.get("good").isEmpty());
        assertTrue(registry.get("bad").isEmpty());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Demo 17: 多 skill 共存,坏的被跳过,好的正常加载")
    void invalid_skill_doesnt_block_others(@TempDir Path tmp) throws IOException {
        Path good = tmp.resolve("good-one");
        Files.createDirectories(good);
        Files.writeString(good.resolve("SKILL.md"), """
                ---
                name: good-one
                description: works fine.
                ---
                # Good
                """);

        Path bad = tmp.resolve("bad-one");
        Files.createDirectories(bad);
        Files.writeString(bad.resolve("SKILL.md"), """
                ---
                name: WRONG
                description: invalid name.
                ---
                """);

        SkillRegistry registry = new SkillRegistry(tmp);
        assertEquals(1, registry.size());
        assertTrue(registry.get("good-one").isPresent());
    }
}
