package com.xilidou.marvis.harness.skill.impl;

import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.http.dto.InputSchema;
import com.xilidou.marvis.harness.skill.Skill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * FileSystemSkill - 真实文件系统操作（s02-s03 完整实现）。
 *
 * <p>提供 4 个工具：
 * <ul>
 *   <li>{@code read_file(path, limit?)} - 读取文件，可选截断</li>
 *   <li>{@code write_file(path, content)} - 写入文件（自动创建父目录）</li>
 *   <li>{@code edit_file(path, old_text, new_text)} - 替换一次匹配的文本</li>
 *   <li>{@code glob(pattern)} - 用通配符匹配文件路径（*.java、**\/*.md 等）</li>
 * </ul>
 *
 * <p>安全设计：所有路径操作走 {@link #safePath(String)}：
 * <ul>
 *   <li>把用户输入的相对路径解析为绝对路径</li>
 *   <li>规范化（消除 ../ 等）</li>
 *   <li>验证最终路径必须在 {@code workdir} 内</li>
 *   <li>否则抛 {@link IllegalArgumentException}（防 path traversal 攻击）</li>
 * </ul>
 *
 * <p>safePath 是**防御编程**，不是 Permission：
 * <ul>
 *   <li>safePath 防的是 LLM **编造**的离谱路径（如 ../../../etc/passwd）</li>
 *   <li>Permission 防的是 LLM **故意**做危险事（如 rm -rf）</li>
 *   <li>两者性质不同，应该并行存在</li>
 * </ul>
 */
@Slf4j
@Component
public class FileSystemSkill implements Skill {

    private static final int MAX_OUTPUT = 50000;

    /**
     * 工作目录。所有路径必须在这个目录内。
     */
    private final Path workdir;

    /**
     * 默认构造器：用当前工作目录（{@code System.getProperty("user.dir")}）。
     * 用于 CLI 场景。
     */
    public FileSystemSkill() {
        this(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize());
    }

    /**
     * 显式指定工作目录（测试场景）。
     */
    public FileSystemSkill(Path workdir) {
        this.workdir = workdir.toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "filesystem";
    }

    @Override
    public String getDescription() {
        return "Read, write, edit files and find files by glob pattern.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(
                new ToolDefinition(
                        "read_file",
                        "Read a file's content. Returns text with line numbers.",
                        InputSchema.object(
                                Map.of(
                                        "path",  Map.of("type", "string",  "description", "File path (relative to workspace)"),
                                        "limit", Map.of("type", "integer", "description", "Max lines to read (optional)")
                                ),
                                "path"
                        )
                ),
                new ToolDefinition(
                        "write_file",
                        "Write content to a file (overwrites if exists, creates parent dirs).",
                        InputSchema.object(
                                Map.of(
                                        "path",    Map.of("type", "string", "description", "File path (relative to workspace)"),
                                        "content", Map.of("type", "string", "description", "Full content to write")
                                ),
                                "path", "content"
                        )
                ),
                new ToolDefinition(
                        "edit_file",
                        "Replace exact text in a file (first match only, fails if not found).",
                        InputSchema.object(
                                Map.of(
                                        "path",     Map.of("type", "string", "description", "File path (relative to workspace)"),
                                        "old_text", Map.of("type", "string", "description", "Exact text to find"),
                                        "new_text", Map.of("type", "string", "description", "Replacement text")
                                ),
                                "path", "old_text", "new_text"
                        )
                ),
                new ToolDefinition(
                        "glob",
                        "Find files by glob pattern (e.g. '*.java', '**/*.md').",
                        InputSchema.object(
                                Map.of(
                                        "pattern", Map.of("type", "string", "description", "Glob pattern to match")
                                ),
                                "pattern"
                        )
                )
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String tool = call.getToolName();
        Map<String, Object> args = call.getArguments();
        try {
            return switch (tool) {
                case "read_file"  -> readFile((String) args.get("path"), (Integer) args.get("limit"));
                case "write_file" -> writeFile((String) args.get("path"), (String) args.get("content"));
                case "edit_file"  -> editFile(
                        (String) args.get("path"),
                        (String) args.get("old_text"),
                        (String) args.get("new_text")
                );
                case "glob"       -> glob((String) args.get("pattern"));
                default -> new ToolResult(false, "Unknown tool: " + tool);
            };
        } catch (IllegalArgumentException e) {
            // safePath 拦截
            log.warn("Path validation failed for {}: {}", tool, e.getMessage());
            return new ToolResult(false, "Error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Tool {} failed", tool, e);
            return new ToolResult(false, "Error: " + e.getMessage());
        }
    }

    // ── 工具实现 ────────────────────────────────────────────────

    /**
     * 读文件，可选截断到前 N 行。
     */
    private ToolResult readFile(String userPath, Integer limit) throws IOException {
        if (userPath == null) return new ToolResult(false, "Error: path is required");

        Path file = safePath(userPath);
        if (!Files.exists(file)) {
            return new ToolResult(false, "Error: file not found: " + userPath);
        }
        if (Files.isDirectory(file)) {
            return new ToolResult(false, "Error: path is a directory, not a file: " + userPath);
        }

        List<String> lines = Files.readAllLines(file);
        if (limit != null && limit > 0 && limit < lines.size()) {
            int dropped = lines.size() - limit;
            lines = new ArrayList<>(lines.subList(0, limit));
            lines.add("... (" + dropped + " more lines)");
        }

        String output = String.join("\n", lines);
        if (output.length() > MAX_OUTPUT) {
            output = output.substring(0, MAX_OUTPUT) + "\n... (truncated to " + MAX_OUTPUT + " chars)";
        }
        return new ToolResult(true, output);
    }

    /**
     * 写文件，自动创建父目录。
     */
    private ToolResult writeFile(String userPath, String content) throws IOException {
        if (userPath == null) return new ToolResult(false, "Error: path is required");
        if (content == null) return new ToolResult(false, "Error: content is required");

        Path file = safePath(userPath);
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, content);

        return new ToolResult(true, String.format("Wrote %d bytes to %s", content.length(), userPath));
    }

    /**
     * 替换一次匹配的文本。如果 old_text 不在文件里，失败。
     */
    private ToolResult editFile(String userPath, String oldText, String newText) throws IOException {
        if (userPath == null) return new ToolResult(false, "Error: path is required");
        if (oldText == null || newText == null) {
            return new ToolResult(false, "Error: old_text and new_text are required");
        }

        Path file = safePath(userPath);
        if (!Files.exists(file)) {
            return new ToolResult(false, "Error: file not found: " + userPath);
        }

        String text = Files.readString(file);
        int idx = text.indexOf(oldText);
        if (idx < 0) {
            return new ToolResult(false,
                    "Error: old_text not found in " + userPath +
                            " (length=" + oldText.length() + ")");
        }

        String updated = text.substring(0, idx) + newText + text.substring(idx + oldText.length());
        Files.writeString(file, updated);
        return new ToolResult(true, "Edited " + userPath);
    }

    /**
     * Glob 匹配。Java NIO 的 PathMatcher 直接支持 glob 语法。
     * 例：{@code *.java}（当前目录所有 .java）/{@code **\/*.md}（递归所有 md）。
     */
    private ToolResult glob(String pattern) throws IOException {
        if (pattern == null || pattern.isBlank()) {
            return new ToolResult(false, "Error: pattern is required");
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> matches = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(workdir)) {
            stream
                    .filter(p -> !p.equals(workdir))
                    .forEach(p -> {
                        Path rel = workdir.relativize(p);
                        if (matcher.matches(rel)) {
                            matches.add(rel.toString());
                        }
                    });
        }

        matches.sort(Comparator.naturalOrder());

        if (matches.isEmpty()) {
            return new ToolResult(true, "(no matches)");
        }
        return new ToolResult(true, String.join("\n", matches));
    }

    // ── 路径安全防御 ────────────────────────────────────────────

    /**
     * 把用户输入的路径解析、规范化，并验证它在 workdir 内。
     *
     * <p>防御 path traversal 攻击：
     * <ul>
     *   <li>{@code ../../etc/passwd} → resolve 后落到 workdir 外 → 拒绝</li>
     *   <li>{@code /etc/passwd}      → 绝对路径直接落到 workdir 外 → 拒绝</li>
     *   <li>符号链接到外部目录的情况 normalize 不会跟随，但读写时仍可能逃逸；
     *       这里采用"路径字符串前缀检查"，对学习项目够用。生产级应该用
     *       {@link Path#toRealPath} 解析符号链接。</li>
     * </ul>
     */
    Path safePath(String userPath) {
        if (userPath == null) {
            throw new IllegalArgumentException("path is null");
        }
        Path resolved = workdir.resolve(userPath).toAbsolutePath().normalize();
        if (!resolved.startsWith(workdir)) {
            throw new IllegalArgumentException(
                    "Path escapes workspace: " + userPath +
                            " (resolved=" + resolved + ", workdir=" + workdir + ")");
        }
        return resolved;
    }

    /**
     * 暴露 workdir 给测试用。
     */
    Path getWorkdir() {
        return workdir;
    }
}
