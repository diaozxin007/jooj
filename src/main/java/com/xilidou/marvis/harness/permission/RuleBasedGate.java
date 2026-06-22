package com.xilidou.marvis.harness.permission;

import com.xilidou.marvis.harness.http.dto.ToolUseBlock;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * Gate 2：规则匹配。
 *
 * <p>对应 Python s03 第 159 行的 {@code PERMISSION_RULES}。
 * 命中后返回 {@link PermissionDecision#ASK}，让 Gate 3 去问用户。
 *
 * <p>当前内置 2 条规则（与 Python 版对齐）：
 * <ul>
 *   <li>{@code write_file} / {@code edit_file} 写到 workspace **外** → ASK
 *       <br>注意：Skill 层的 {@code safePath()} 会直接 throw 拒绝。这里这条规则
 *       理论上不会被触发（safePath 比这先一步），但保留是因为有些场景可能允许
 *       写到外部（用户明确同意）。</li>
 *   <li>{@code bash} 命令含 destructive 关键字（{@code rm }, {@code > /etc/},
 *       {@code chmod 777}）→ ASK</li>
 * </ul>
 *
 * <p>设计上这些规则是**可扩展**的：通过构造函数传入自定义 {@link Rule} 列表，
 * 将来 Week 4-12 加新规则不用改 Gate 代码。
 */
public class RuleBasedGate implements PermissionGate {

    /** 写工具：path 字段在哪些工具里出现 */
    private static final Set<String> WRITE_TOOLS = Set.of("write_file", "edit_file");

    /** 默认 destructive 关键字（s03 + 常见高危） */
    public static final List<String> DEFAULT_DESTRUCTIVE_KEYWORDS = List.of(
            "rm ",
            "rm\t",
            "> /etc/",
            "chmod 777",
            "chmod -R 777",
            "git push --force",
            "git reset --hard"
    );

    private final Path workdir;
    private final List<String> destructiveKeywords;

    public RuleBasedGate() {
        this(
                Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize(),
                DEFAULT_DESTRUCTIVE_KEYWORDS
        );
    }

    public RuleBasedGate(Path workdir, List<String> destructiveKeywords) {
        this.workdir = workdir.toAbsolutePath().normalize();
        this.destructiveKeywords = List.copyOf(destructiveKeywords);
    }

    @Override
    public PermissionResult check(ToolUseBlock toolUse) {
        String name = toolUse.getName();
        var input = toolUse.getInput();

        // 规则 1：写文件到 workspace 外
        if (WRITE_TOOLS.contains(name) && input != null && input.has("path")) {
            String path = input.get("path").asText();
            try {
                Path resolved = workdir.resolve(path).toAbsolutePath().normalize();
                if (!resolved.startsWith(workdir)) {
                    return PermissionResult.ask(
                            "Writing outside workspace: " + path + " → " + resolved
                    );
                }
            } catch (Exception e) {
                // resolve 失败（极少见），保守 ask
                return PermissionResult.ask("Path resolution failed: " + path);
            }
        }

        // 规则 2：bash destructive 关键字
        if ("bash".equals(name) && input != null && input.has("command")) {
            String command = input.get("command").asText();
            for (String kw : destructiveKeywords) {
                if (command.contains(kw)) {
                    return PermissionResult.ask(
                            "Potentially destructive command (matched '" + kw + "'): " + command
                    );
                }
            }
        }

        return PermissionResult.allow();
    }
}
