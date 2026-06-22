package com.xilidou.marvis.harness.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SkillRegistry - 启动时扫描 skills/ 目录，构建 Skill catalog。
 *
 * <p>对应 Python s07 的 {@code _scan_skills()} + {@code SKILL_REGISTRY} 全局 dict。
 *
 * <h3>目录约定</h3>
 *
 * <pre>
 *   skills/
 *     code-review/
 *       SKILL.md          ← 必须有，含 YAML frontmatter
 *     agent-builder/
 *       SKILL.md
 *     ...                  ← 子目录名不重要，SKILL.md 里的 name 字段才是唯一标识
 * </pre>
 *
 * <h3>SKILL.md 格式</h3>
 *
 * <pre>
 *   ---
 *   name: code-review
 *   description: Perform thorough code reviews with security, performance...
 *   ---
 *
 *   # Code Review Skill
 *   ...
 * </pre>
 *
 * <h3>路径配置</h3>
 *
 * <p>{@code marvis.skills.dir} 配置项指定 skills 目录位置（默认 {@code skills}，相对当前工作目录）。
 * Spring 场景从 application.properties 读，非 Spring 场景从默认值兜底。
 */
@Component
@Slf4j
public class SkillRegistry {

    /** 文件名约定：每个 skill 子目录下的入口文件 */
    private static final String SKILL_FILE = "SKILL.md";

    /** YAML frontmatter 的分隔符 */
    private static final String FRONTMATTER_DELIM = "---";

    private final Path skillsDir;
    private final Map<String, Skill> registry = new LinkedHashMap<>();

    /**
     * Spring 注入构造器：从 application.properties 读 skills 目录路径，默认 "skills"。
     *
     * <p>{@code @Autowired} 显式标注是必要的——本类有 2 个构造器，
     * Spring 多构造器陷阱（已踩过 3 次：ToolRegistry / TodoTool / SkillRegistry）。
     */
    @Autowired
    public SkillRegistry(@Value("${marvis.skills.dir:skills}") String skillsDirPath) {
        this(Paths.get(skillsDirPath));
    }

    /**
     * 测试用构造器：直接传 Path。
     */
    public SkillRegistry(Path skillsDir) {
        this.skillsDir = skillsDir.toAbsolutePath().normalize();
        scanSkills();
    }

    /**
     * 扫描 skillsDir 下所有子目录，加载 SKILL.md。
     *
     * <p>容错策略：
     * <ul>
     *   <li>skillsDir 不存在 → 静默（log.info）+ 空 registry</li>
     *   <li>某个 SKILL.md 解析失败 → log.warn + 跳过该 skill，不让其他 skill 受影响</li>
     *   <li>frontmatter 缺失 / 格式错 → 用目录名当 name，body 第一行当 description（fallback）</li>
     * </ul>
     */
    private void scanSkills() {
        if (!Files.isDirectory(skillsDir)) {
            log.info("Skills directory not found: {} (no skills loaded)", skillsDir);
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir, Files::isDirectory)) {
            for (Path subdir : stream) {
                Path manifest = subdir.resolve(SKILL_FILE);
                if (!Files.isRegularFile(manifest)) continue;

                try {
                    Skill skill = loadOne(subdir, manifest);
                    registry.put(skill.getName(), skill);
                    log.info("Loaded skill: {} - {}", skill.getName(),
                            truncate(skill.getDescription(), 60));
                } catch (Exception e) {
                    log.warn("Failed to load skill from {}: {}", manifest, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan skills dir {}: {}", skillsDir, e.getMessage());
        }

        log.info("SkillRegistry initialized: {} skills loaded from {}", registry.size(), skillsDir);
    }

    private Skill loadOne(Path subdir, Path manifest) throws IOException {
        String raw = Files.readString(manifest);
        Map<String, Object> meta = parseFrontmatter(raw);

        String name = (String) meta.getOrDefault("name", subdir.getFileName().toString());
        String description = (String) meta.getOrDefault("description", firstNonEmptyLine(raw));

        return new Skill(name, description, raw);
    }

    /**
     * 解析 YAML frontmatter。如果文件不以 {@code ---} 开头或格式错，返回空 map。
     */
    private Map<String, Object> parseFrontmatter(String text) {
        if (!text.startsWith(FRONTMATTER_DELIM)) return Collections.emptyMap();
        // 跳过开头的 ---，找下一个 --- 分隔符
        int secondDelim = text.indexOf("\n" + FRONTMATTER_DELIM, FRONTMATTER_DELIM.length());
        if (secondDelim < 0) return Collections.emptyMap();

        String yamlPart = text.substring(FRONTMATTER_DELIM.length(), secondDelim).trim();
        try {
            Yaml yaml = new Yaml();
            Object parsed = yaml.load(yamlPart);
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) parsed;
                return map;
            }
            return Collections.emptyMap();
        } catch (YAMLException e) {
            log.debug("YAML parse failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 取文件第一行非空文本（fallback 用）。如果没找到，返回 "(no description)"。
     */
    private String firstNonEmptyLine(String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim().replaceAll("^#+\\s*", "");
            if (!trimmed.isEmpty() && !trimmed.equals(FRONTMATTER_DELIM)) {
                return trimmed;
            }
        }
        return "(no description)";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ── 公开 API ────────────────────────────────────────────────

    /**
     * 列出所有 skill 的 name + description（拼成多行字符串），用于注入 SYSTEM prompt。
     *
     * <p>没 skill 时返回空字符串（调用方判断）。
     */
    public String catalog() {
        if (registry.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Skill skill : registry.values()) {
            sb.append("- **").append(skill.getName()).append("**: ")
              .append(truncate(skill.getDescription(), 200)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 按 name 加载完整 skill（含 body）。找不到返回 {@link java.util.Optional#empty}。
     */
    public java.util.Optional<Skill> get(String name) {
        return java.util.Optional.ofNullable(registry.get(name));
    }

    /**
     * 所有可用 skill 名字（测试 + 调试用）。
     */
    public List<String> listNames() {
        return List.copyOf(registry.keySet());
    }

    public int size() {
        return registry.size();
    }

    public Path getSkillsDir() {
        return skillsDir;
    }
}
