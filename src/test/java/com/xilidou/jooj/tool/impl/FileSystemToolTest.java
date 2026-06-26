package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link FileSystemTool} 的核心行为。
 *
 * <p>测试组织：
 * <ul>
 *   <li>{@link SafePathTest} - 路径安全防御（最重要，安全代码）</li>
 *   <li>{@link ReadFileTest} - 读文件 + 截断 + 错误</li>
 *   <li>{@link WriteFileTest} - 写文件 + 创建父目录</li>
 *   <li>{@link EditFileTest} - 替换文本 + old_text 不存在</li>
 *   <li>{@link GlobTest} - 通配符匹配</li>
 * </ul>
 *
 * <p>用 {@code @TempDir} 让每个测试有独立 workspace，跑完自动清理。
 */
class FileSystemToolTest {

    @TempDir
    Path workdir;

    private FileSystemTool skill;

    @BeforeEach
    void setUp() {
        skill = new FileSystemTool(workdir);
    }

    // ────────────────────────────────────────────────────────────
    //  路径安全防御（最重要）
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("safePath: workspace 隔离防御")
    class SafePathTest {

        @Test
        @DisplayName("normal relative path → resolves inside workdir")
        void should_allow_normal_path() {
            Path resolved = skill.safePath("foo/bar.txt");
            assertTrue(resolved.startsWith(workdir));
            assertTrue(resolved.endsWith("foo/bar.txt"));
        }

        @Test
        @DisplayName("../../../etc/passwd → rejected")
        void should_block_path_traversal() {
            assertThrows(IllegalArgumentException.class,
                    () -> skill.safePath("../../../etc/passwd"));
        }

        @Test
        @DisplayName("absolute /etc/passwd → rejected")
        void should_block_absolute_path_outside_workdir() {
            assertThrows(IllegalArgumentException.class,
                    () -> skill.safePath("/etc/passwd"));
        }

        @Test
        @DisplayName("a/../b → resolves to workdir/b (normalize 把 .. 抵消)")
        void should_normalize_safe_dotdot() {
            Path resolved = skill.safePath("a/../b");
            assertEquals(workdir.resolve("b").toAbsolutePath().normalize(), resolved);
        }

        @Test
        @DisplayName("null path → throws")
        void should_reject_null() {
            assertThrows(IllegalArgumentException.class, () -> skill.safePath(null));
        }
    }

    // ────────────────────────────────────────────────────────────
    //  read_file
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("read_file")
    class ReadFileTest {

        @Test
        @DisplayName("reads existing file")
        void should_read_existing_file() throws IOException {
            Files.writeString(workdir.resolve("hello.txt"), "line 1\nline 2\nline 3");

            ToolResult result = skill.execute(call("read_file", Map.of("path", "hello.txt")));

            assertTrue(result.isSuccess());
            assertEquals("line 1\nline 2\nline 3", result.getOutput());
        }

        @Test
        @DisplayName("limit truncates output")
        void should_truncate_with_limit() throws IOException {
            Files.writeString(workdir.resolve("big.txt"), "1\n2\n3\n4\n5");

            ToolResult result = skill.execute(call("read_file", Map.of(
                    "path", "big.txt",
                    "limit", 2
            )));

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().startsWith("1\n2"));
            assertTrue(result.getOutput().contains("3 more lines"),
                    "应该提示还有 3 行被截断，实际：" + result.getOutput());
        }

        @Test
        @DisplayName("missing file → error")
        void should_fail_on_missing_file() {
            ToolResult result = skill.execute(call("read_file", Map.of("path", "nope.txt")));

            assertFalse(result.isSuccess());
            assertTrue(result.getOutput().contains("not found"));
        }

        @Test
        @DisplayName("path traversal → blocked")
        void should_block_traversal() {
            ToolResult result = skill.execute(call("read_file", Map.of("path", "../etc/passwd")));

            assertFalse(result.isSuccess());
            assertTrue(result.getOutput().contains("escapes workspace"));
        }

        @Test
        @DisplayName("directory instead of file → error")
        void should_fail_on_directory() throws IOException {
            Files.createDirectory(workdir.resolve("subdir"));

            ToolResult result = skill.execute(call("read_file", Map.of("path", "subdir")));

            assertFalse(result.isSuccess());
            assertTrue(result.getOutput().contains("directory"));
        }
    }

    // ────────────────────────────────────────────────────────────
    //  write_file
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("write_file")
    class WriteFileTest {

        @Test
        @DisplayName("writes new file")
        void should_write_new_file() throws IOException {
            ToolResult result = skill.execute(call("write_file", Map.of(
                    "path", "out.txt",
                    "content", "hello"
            )));

            assertTrue(result.isSuccess());
            assertEquals("hello", Files.readString(workdir.resolve("out.txt")));
        }

        @Test
        @DisplayName("creates missing parent directories")
        void should_create_parent_dirs() throws IOException {
            ToolResult result = skill.execute(call("write_file", Map.of(
                    "path", "a/b/c/deep.txt",
                    "content", "nested"
            )));

            assertTrue(result.isSuccess());
            assertEquals("nested", Files.readString(workdir.resolve("a/b/c/deep.txt")));
        }

        @Test
        @DisplayName("overwrites existing file")
        void should_overwrite_existing() throws IOException {
            Files.writeString(workdir.resolve("file.txt"), "old");

            skill.execute(call("write_file", Map.of(
                    "path", "file.txt",
                    "content", "new"
            )));

            assertEquals("new", Files.readString(workdir.resolve("file.txt")));
        }

        @Test
        @DisplayName("path traversal → blocked")
        void should_block_traversal() {
            ToolResult result = skill.execute(call("write_file", Map.of(
                    "path", "../escape.txt",
                    "content", "evil"
            )));

            assertFalse(result.isSuccess());
            assertTrue(result.getOutput().contains("escapes workspace"));
        }
    }

    // ────────────────────────────────────────────────────────────
    //  edit_file
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("edit_file")
    class EditFileTest {

        @Test
        @DisplayName("replaces first occurrence")
        void should_replace_first_match() throws IOException {
            Files.writeString(workdir.resolve("code.txt"), "foo\nbar\nfoo");

            ToolResult result = skill.execute(call("edit_file", Map.of(
                    "path", "code.txt",
                    "old_text", "foo",
                    "new_text", "FOO"
            )));

            assertTrue(result.isSuccess());
            // 只替换第一次匹配
            assertEquals("FOO\nbar\nfoo", Files.readString(workdir.resolve("code.txt")));
        }

        @Test
        @DisplayName("old_text not found → error, file unchanged")
        void should_fail_when_old_text_missing() throws IOException {
            Files.writeString(workdir.resolve("code.txt"), "abc");

            ToolResult result = skill.execute(call("edit_file", Map.of(
                    "path", "code.txt",
                    "old_text", "xyz",
                    "new_text", "XYZ"
            )));

            assertFalse(result.isSuccess());
            assertTrue(result.getOutput().contains("not found"));
            // 文件没动
            assertEquals("abc", Files.readString(workdir.resolve("code.txt")));
        }
    }

    // ────────────────────────────────────────────────────────────
    //  glob
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("glob")
    class GlobTest {

        @Test
        @DisplayName("matches files at top level")
        void should_match_top_level() throws IOException {
            Files.writeString(workdir.resolve("a.java"), "");
            Files.writeString(workdir.resolve("b.java"), "");
            Files.writeString(workdir.resolve("c.txt"), "");

            ToolResult result = skill.execute(call("glob", Map.of("pattern", "*.java")));

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("a.java"));
            assertTrue(result.getOutput().contains("b.java"));
            assertFalse(result.getOutput().contains("c.txt"));
        }

        @Test
        @DisplayName("matches recursively with **")
        void should_match_recursive() throws IOException {
            Files.createDirectories(workdir.resolve("src/main/java"));
            Files.writeString(workdir.resolve("src/main/java/Main.java"), "");
            Files.writeString(workdir.resolve("src/main/java/Util.java"), "");

            ToolResult result = skill.execute(call("glob", Map.of("pattern", "**/*.java")));

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("Main.java"));
            assertTrue(result.getOutput().contains("Util.java"));
        }

        @Test
        @DisplayName("no matches → '(no matches)'")
        void should_return_no_matches() {
            ToolResult result = skill.execute(call("glob", Map.of("pattern", "*.nonexistent")));

            assertTrue(result.isSuccess());
            assertEquals("(no matches)", result.getOutput());
        }
    }

    // ── s18:ExecutionContext.cwd 改变相对路径解析基准 ───────────────

    @org.junit.jupiter.api.Nested
    @DisplayName("s18: ctx.cwd 决定相对路径解析基准")
    class ExecutionContextCwd {

        @Test
        @DisplayName("read_file with ctx.cwd:相对路径在 cwd 下解析,不是 workdir")
        void read_file_uses_ctx_cwd_as_base() throws Exception {
            // 模拟 worktree 在 workdir 子目录
            Path subDir = workdir.resolve(".worktrees/auth");
            Files.createDirectories(subDir);
            Files.writeString(subDir.resolve("config.py"), "wt content");

            // workdir 根也有 config.py(故意同名,验证不被读到)
            Files.writeString(workdir.resolve("config.py"), "main content");

            // ctx.cwd = workdir/.worktrees/auth → 相对路径"config.py"应解析到子目录
            com.xilidou.jooj.tool.ExecutionContext ctx =
                    com.xilidou.jooj.tool.ExecutionContext.inWorktree("alice", "auth", subDir);
            ToolResult result = skill.execute(call("read_file", Map.of("path", "config.py")), ctx);

            assertTrue(result.isSuccess());
            assertEquals("wt content", result.getOutput(),
                    "应该读到 worktree 下的 config.py,不是 workdir 根的");
        }

        @Test
        @DisplayName("write_file with ctx.cwd:写到 cwd 子树,不是 workdir 根")
        void write_file_uses_ctx_cwd_as_base() throws Exception {
            Path subDir = workdir.resolve(".worktrees/auth");
            Files.createDirectories(subDir);

            com.xilidou.jooj.tool.ExecutionContext ctx =
                    com.xilidou.jooj.tool.ExecutionContext.inWorktree("alice", "auth", subDir);
            ToolResult result = skill.execute(
                    call("write_file", Map.of("path", "new.txt", "content", "hi")), ctx);

            assertTrue(result.isSuccess());
            assertTrue(Files.exists(subDir.resolve("new.txt")));
            assertFalse(Files.exists(workdir.resolve("new.txt")),
                    "不应写到 workdir 根");
        }

        @Test
        @DisplayName("ctx.cwd 路径仍受 workdir 安全 root 保护(防 ../../etc 逃逸)")
        void ctx_cwd_still_blocked_by_workdir_security() throws Exception {
            Path subDir = workdir.resolve(".worktrees/auth");
            Files.createDirectories(subDir);

            com.xilidou.jooj.tool.ExecutionContext ctx =
                    com.xilidou.jooj.tool.ExecutionContext.inWorktree("alice", "auth", subDir);
            // 队友试图从 worktree 内部用 ../../.. 跳出 workdir
            ToolResult result = skill.execute(
                    call("read_file", Map.of("path", "../../../etc/passwd")), ctx);

            assertFalse(result.isSuccess());
            assertTrue(result.getOutput().toLowerCase().contains("escapes"));
        }

        @Test
        @DisplayName("ctx 为 null / lead():行为跟旧 execute(call) 完全一致")
        void null_or_lead_ctx_falls_back_to_workdir() throws Exception {
            Files.writeString(workdir.resolve("config.py"), "main content");

            ToolResult oldApi = skill.execute(call("read_file", Map.of("path", "config.py")));
            ToolResult newApiLead = skill.execute(
                    call("read_file", Map.of("path", "config.py")),
                    com.xilidou.jooj.tool.ExecutionContext.lead());

            assertEquals(oldApi.getOutput(), newApiLead.getOutput());
        }
    }

    // ── helpers ────────────────────────────────────────────────
    private static ToolCall call(String tool, Map<String, Object> args) {
        return new ToolCall(tool, args);
    }
}
