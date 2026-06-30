package com.xilidou.jooj.skill;

import com.xilidou.jooj.bootstrap.JoojHome;
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
 * <p>{@code jooj.skills.dir} 配置项指定 skills 目录位置（默认 {@code skills}，相对当前工作目录）。
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
    private final boolean scanGlobalTiers;
    private final Map<String, Skill> registry = new LinkedHashMap<>();

    /**
     * 节流:上次成功 rescan 的 epoch ms。{@link #rescan(boolean)} 用。
     * 用 {@code volatile} 而非 AtomicLong —— 写在 synchronized 块里,这里只要保证读可见。
     */
    private volatile long lastScanMs = 0L;

    /** 节流间隔。1s 内重复 rescan() 直接 no-op,除非 force=true。 */
    private static final long RESCAN_THROTTLE_MS = 1000;

    /**
     * Spring 注入构造器：从 application.properties 读 skills 目录路径，默认 "skills"。
     *
     * <p>{@code @Autowired} 显式标注是必要的——本类有多个构造器，
     * Spring 多构造器陷阱（已踩过 3 次：ToolRegistry / TodoTool / SkillRegistry）。
     *
     * <p>生产场景默认扫所有 3 层(项目 + ~/.jooj/skills + ~/.claude/skills)。
     */
    @Autowired
    public SkillRegistry(@Value("${jooj.skills.dir:skills}") String skillsDirPath) {
        this(Paths.get(skillsDirPath), true);
    }

    /**
     * 测试用构造器(单参):只扫给定目录,**不扫全局** —— 用 {@code @TempDir} 时不被
     * 真实 {@code ~/.jooj/skills/} 或 {@code ~/.claude/skills/} 污染。
     */
    public SkillRegistry(Path skillsDir) {
        this(skillsDir, false);
    }

    /**
     * 完整构造器(测试 / 高级用法)。
     *
     * @param skillsDir       项目级 skill 目录(第 1 层)
     * @param scanGlobalTiers 是否额外扫 {@code ~/.jooj/skills/} 和 {@code ~/.claude/skills/}
     */
    public SkillRegistry(Path skillsDir, boolean scanGlobalTiers) {
        this.skillsDir = skillsDir.toAbsolutePath().normalize();
        this.scanGlobalTiers = scanGlobalTiers;
        scanSkills();
        this.lastScanMs = System.currentTimeMillis();   // 标记初始扫已完成,1s 内 rescan(false) 节流
    }

    /**
     * 扫描 skill 目录。
     *
     * <h3>三层 layout(参考 hermes / claude-code 的 user-vs-project 分层)</h3>
     *
     * <p>{@code scanGlobalTiers=true} 时(生产 = Spring 注入路径)扫全部 3 层:
     * <ol>
     *   <li>项目级 {@code <cwd>/skills/}(由 {@code jooj.skills.dir} 配置,默认 {@code skills}) ——
     *       先扫,占位优先</li>
     *   <li>jooj 全局级 {@code ~/.jooj/skills/} —— jooj 用户专属 skill</li>
     *   <li>Claude Code 共享池 {@code ~/.claude/skills/} —— 跟 Claude Code 共用,
     *       适合 {@code npx skills add ... -g} 安装的通用 skill(如 find-skills / web-research)</li>
     * </ol>
     *
     * <p>{@code scanGlobalTiers=false} 时(测试路径)只扫第 1 层 —— 避免 {@code @TempDir} 被
     * 真实文件系统污染。
     *
     * <p>同名 skill 按上面顺序的**先到先得**(后扫的会被跳过)。
     *
     * <p><b>跨 agent 兼容性警告</b>:{@code ~/.claude/skills/} 里有些 skill 引用 Claude Code
     * 专属 slash command(如 {@code /loop})或工具(如 {@code Workflow}/{@code Monitor}),
     * 在 jooj 里加载不报错,但 LLM 实际调用时会失败。这是已知耦合代价。
     *
     * <p>容错:任一目录不存在 → 静默 log.info,不影响其他目录的扫描。
     */
    /**
     * 强制重扫所有 skill 目录,刷新 in-memory registry。
     *
     * <h3>什么时候调</h3>
     *
     * <ul>
     *   <li>用户在浏览器 sidebar 点 ↻ → POST /api/skills/rescan(force=true)</li>
     *   <li>每个 LLM turn 之前 SystemPromptAssembler 自动调(force=false,1s 节流)
     *       — 让会话中 LLM 在 bash 里装的新 skill 下一轮自动可见</li>
     * </ul>
     *
     * <h3>节流</h3>
     *
     * <p>{@code force=false} 时,1s 内重复调返回 cached size 不实际扫盘。
     * 防止极端情况(如 LLM 每秒多 turn)炸 IO。
     *
     * <h3>原子性</h3>
     *
     * <p>整个方法 {@code synchronized} —— scan 期间其他线程读 {@link #catalog()} /
     * {@link #get(String)} 会等;但 scan 通常 < 50ms(几十个 SKILL.md),延迟可忽略。
     * 不做 read-write split 也不做 swap-and-replace,简单可靠。
     *
     * <p>scan 期间先 clear 旧 registry 再重建 —— 同名 skill 的优先级规则({@code scanDir}
     * 用 {@code containsKey} 判断)在 rebuild 中重新生效。
     *
     * @param force 跳过节流,无条件扫
     * @return 扫描后的 skill 总数(节流命中时返回当前 cached size)
     */
    public synchronized int rescan(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastScanMs) < RESCAN_THROTTLE_MS) {
            return registry.size();
        }
        registry.clear();
        scanSkills();
        lastScanMs = now;
        return registry.size();
    }

    private void scanSkills() {
        // 第 1 层:项目级 — 优先级最高,永远扫
        scanDir(skillsDir, "project");

        if (!scanGlobalTiers) {
            log.info("SkillRegistry initialized: {} skills loaded (project-only, project={})",
                    registry.size(), skillsDir);
            return;
        }

        // 第 2 层:jooj 全局
        Path joojSkillsDir = JoojHome.getHomePath().resolve("skills");
        if (!joojSkillsDir.equals(skillsDir)) {
            // 避免在测试用 ~/.jooj/skills/ 当 project skillsDir 时双扫一遍
            scanDir(joojSkillsDir, "jooj-global");
        }

        // 第 3 层:Claude Code 共享池
        Path claudeSkillsDir = Paths.get(System.getProperty("user.home"), ".claude", "skills");
        if (!claudeSkillsDir.equals(skillsDir) && !claudeSkillsDir.equals(joojSkillsDir)) {
            scanDir(claudeSkillsDir, "claude-shared");
        }

        log.info("SkillRegistry initialized: {} skills loaded (project={}, jooj-global=~/.jooj/skills/, claude-shared=~/.claude/skills/)",
                registry.size(), skillsDir);
    }

    /**
     * 扫描一个目录,加载所有 SKILL.md。
     *
     * <p>容错策略:
     * <ul>
     *   <li>dir 不存在 → 静默(log.info)+ 不加载</li>
     *   <li>某个 SKILL.md 解析失败 → log.warn + 跳过</li>
     *   <li>{@code registry} 已有同名 skill → 跳过(项目级先扫,所以这表示全局级被项目级覆盖)</li>
     * </ul>
     *
     * @param dir 要扫描的目录
     * @param tier "project" 或 "global",仅用于日志可读性
     */
    private void scanDir(Path dir, String tier) {
        if (!Files.isDirectory(dir)) {
            log.info("Skills directory not found ({}): {} — skipping", tier, dir);
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path subdir : stream) {
                Path manifest = subdir.resolve(SKILL_FILE);
                if (!Files.isRegularFile(manifest)) continue;

                try {
                    Skill skill = loadOne(subdir, manifest);
                    if (registry.containsKey(skill.getName())) {
                        // project 先扫,这里只可能是 global 试图覆盖 — 跳过保留 project 版本
                        log.info("Skipping {} skill {} (project-level version takes precedence)",
                                tier, skill.getName());
                        continue;
                    }
                    registry.put(skill.getName(), skill);
                    log.info("Loaded {} skill: {} - {}", tier, skill.getName(),
                            truncate(skill.getDescription(), 60));
                } catch (Exception e) {
                    log.warn("Failed to load {} skill from {}: {}", tier, manifest, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan {} skills dir {}: {}", tier, dir, e.getMessage());
        }
    }

    private Skill loadOne(Path subdir, Path manifest) throws IOException {
        String raw = Files.readString(manifest);
        Map<String, Object> meta = parseFrontmatter(raw);

        // s21 Demo 17:对齐 agentskills.io spec,读全 6 个 frontmatter 字段。
        String dirName = subdir.getFileName().toString();
        String name = (String) meta.getOrDefault("name", dirName);
        String description = (String) meta.getOrDefault("description", firstNonEmptyLine(raw));
        String license = stringOrNull(meta.get("license"));
        String compatibility = stringOrNull(meta.get("compatibility"));
        String allowedTools = stringOrNull(meta.get("allowed-tools"));
        Map<String, String> metadata = stringMapOrNull(meta.get("metadata"));

        // 校验:不合规直接抛,scanDir 的 try-catch 会 log.warn 跳过该 skill
        String err;
        if ((err = SkillFrontmatterValidator.validateName(name, dirName)) != null) {
            throw new IOException("SKILL.md frontmatter invalid (" + manifest + "): " + err);
        }
        if ((err = SkillFrontmatterValidator.validateDescription(description)) != null) {
            throw new IOException("SKILL.md frontmatter invalid (" + manifest + "): " + err);
        }
        if ((err = SkillFrontmatterValidator.validateCompatibility(compatibility)) != null) {
            throw new IOException("SKILL.md frontmatter invalid (" + manifest + "): " + err);
        }

        return new Skill(name, description, raw, license, compatibility, metadata, allowedTools);
    }

    /** 把 frontmatter 里的对象转成 String;null / 空白 都返 null。 */
    private static String stringOrNull(Object value) {
        return (value instanceof String s && !s.isBlank()) ? s : null;
    }

    /**
     * 把 frontmatter 里的 metadata 块转成 String→String map。
     * spec 说"map from string keys to string values",但 YAML 反序列化里 value 可能是
     * Integer / Double / Boolean(如 {@code version: 1.0} 不带引号会被解析成 Double 1.0)。
     * 这里统一 toString,容忍。null / 非 Map / 空 Map 都返 null。
     */
    private static Map<String, String> stringMapOrNull(Object value) {
        if (!(value instanceof Map<?, ?> m) || m.isEmpty()) return null;
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : m.entrySet()) {
            if (entry.getKey() == null) continue;
            String key = entry.getKey().toString();
            String val = entry.getValue() == null ? "" : entry.getValue().toString();
            out.put(key, val);
        }
        return out.isEmpty() ? null : out;
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
     *
     * <p>{@code synchronized} 跟 {@link #rescan} 互斥:rescan 期间读会等(< 50ms),
     * 但保证不会在 rebuild 半中间读到部分填充的 registry。
     */
    public synchronized String catalog() {
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
    public synchronized java.util.Optional<Skill> get(String name) {
        return java.util.Optional.ofNullable(registry.get(name));
    }

    /**
     * 所有可用 skill 名字（测试 + 调试用）。
     */
    public synchronized List<String> listNames() {
        return List.copyOf(registry.keySet());
    }

    /**
     * 列出所有 skill 的概要(name + description) —— 给 web sidebar 用。
     *
     * <p>不暴露 {@code body} 字段:body 可能很大(几千 token),前端只需要列表浏览。
     * 真要看 body 就调 {@code load_skill} 工具,在 chat 里看。
     */
    public synchronized List<Map.Entry<String, String>> listSummaries() {
        List<Map.Entry<String, String>> result = new java.util.ArrayList<>(registry.size());
        for (Skill s : registry.values()) {
            result.add(Map.entry(s.getName(),
                    s.getDescription() == null ? "" : s.getDescription()));
        }
        return result;
    }

    public synchronized int size() {
        return registry.size();
    }

    public Path getSkillsDir() {
        return skillsDir;
    }
}
