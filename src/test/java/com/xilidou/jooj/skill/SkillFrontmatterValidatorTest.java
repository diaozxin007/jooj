package com.xilidou.jooj.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link SkillFrontmatterValidator} 按 agentskills.io spec 校验的行为。
 * Demo 17 加 — 自创建 skill 时也复用这套校验,守住格式不退化。
 */
class SkillFrontmatterValidatorTest {

    // ── name 字段 ──

    @Test
    @DisplayName("name: 合法形式")
    void name_valid_forms() {
        assertNull(SkillFrontmatterValidator.validateName("code-review", "code-review"));
        assertNull(SkillFrontmatterValidator.validateName("pdf", "pdf"));
        assertNull(SkillFrontmatterValidator.validateName("data-analysis-2024", "data-analysis-2024"));
    }

    @Test
    @DisplayName("name: 必填")
    void name_required() {
        assertNotNull(SkillFrontmatterValidator.validateName(null, "x"));
        assertNotNull(SkillFrontmatterValidator.validateName("", "x"));
        assertNotNull(SkillFrontmatterValidator.validateName("  ", "x"));
    }

    @Test
    @DisplayName("name: 大写不允许")
    void name_no_uppercase() {
        assertNotNull(SkillFrontmatterValidator.validateName("PDF-Processing", "PDF-Processing"));
    }

    @Test
    @DisplayName("name: 首尾连字符不允许")
    void name_no_leading_trailing_hyphen() {
        assertNotNull(SkillFrontmatterValidator.validateName("-pdf", "-pdf"));
        assertNotNull(SkillFrontmatterValidator.validateName("pdf-", "pdf-"));
    }

    @Test
    @DisplayName("name: 连续连字符不允许")
    void name_no_consecutive_hyphens() {
        assertNotNull(SkillFrontmatterValidator.validateName("pdf--processing", "pdf--processing"));
    }

    @Test
    @DisplayName("name: 必须等于父目录名")
    void name_must_match_dir() {
        assertNotNull(SkillFrontmatterValidator.validateName("foo", "bar"));
    }

    @Test
    @DisplayName("name: 64 字符上限")
    void name_max_length() {
        String len64 = "a".repeat(64);
        String len65 = "a".repeat(65);
        assertNull(SkillFrontmatterValidator.validateName(len64, len64));
        assertNotNull(SkillFrontmatterValidator.validateName(len65, len65));
    }

    // ── description 字段 ──

    @Test
    @DisplayName("description: 必填")
    void description_required() {
        assertNotNull(SkillFrontmatterValidator.validateDescription(null));
        assertNotNull(SkillFrontmatterValidator.validateDescription(""));
        assertNotNull(SkillFrontmatterValidator.validateDescription("   "));
    }

    @Test
    @DisplayName("description: 1024 字符上限")
    void description_max_length() {
        assertNull(SkillFrontmatterValidator.validateDescription("a".repeat(1024)));
        assertNotNull(SkillFrontmatterValidator.validateDescription("a".repeat(1025)));
    }

    // ── compatibility 字段 ──

    @Test
    @DisplayName("compatibility: null/blank 视作未声明,合法")
    void compatibility_optional() {
        assertNull(SkillFrontmatterValidator.validateCompatibility(null));
        assertNull(SkillFrontmatterValidator.validateCompatibility(""));
        assertNull(SkillFrontmatterValidator.validateCompatibility("   "));
    }

    @Test
    @DisplayName("compatibility: 500 字符上限")
    void compatibility_max_length() {
        assertNull(SkillFrontmatterValidator.validateCompatibility("a".repeat(500)));
        assertNotNull(SkillFrontmatterValidator.validateCompatibility("a".repeat(501)));
    }
}
