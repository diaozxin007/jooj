package com.xilidou.jooj.tool;

import com.xilidou.jooj.tool.impl.BashTool;
import com.xilidou.jooj.tool.impl.FileSystemTool;
import com.xilidou.jooj.tool.impl.SessionSearchTool;
import com.xilidou.jooj.tool.impl.TaskTool;
import com.xilidou.jooj.tool.impl.TeamTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * s22 D-11-a:验证主要工具的 {@link Tool#summary(ToolCall)} override,
 * 保证给前端 "正在:xxx" loading 气泡的 UX 摘要不回归。
 *
 * <p>使用真实 tool 类的 {@code summary} 方法(不 mock,不需 Spring)——
 * summary 是纯字符串组装,无 IO 依赖。
 */
class ToolSummaryTest {

    // ── 通用 ────────────────────────────────────────────────

    @Test
    @DisplayName("默认 summary:null call → tool name")
    void default_summary_null_call() {
        Tool t = new StubTool();
        assertEquals("stub", t.summary(null));
    }

    @Test
    @DisplayName("默认 summary:name + 参数缩略")
    void default_summary_truncates_long_args() {
        Tool t = new StubTool();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("a", "x".repeat(200));
        String s = t.summary(new ToolCall("stub", args));
        assertTrue(s.startsWith("stub"));
        assertTrue(s.length() <= "stub".length() + 63, "应截断,实际:" + s.length());
    }

    // ── BashTool ─────────────────────────────────────────────

    @Test
    @DisplayName("BashTool 摘要:$ <command>")
    void bash_summary_uses_command() {
        Tool t = new BashTool();
        Map<String, Object> args = Map.of("command", "ls -la /tmp");
        assertEquals("$ ls -la /tmp", t.summary(new ToolCall("bash", args)));
    }

    @Test
    @DisplayName("BashTool 摘要:超长 command 截断")
    void bash_summary_truncates() {
        Tool t = new BashTool();
        String longCmd = "echo " + "x".repeat(200);
        String s = t.summary(new ToolCall("bash", Map.of("command", longCmd)));
        assertTrue(s.startsWith("$ "));
        assertTrue(s.endsWith("..."));
        assertTrue(s.length() < 80, "总长应受控,实际:" + s.length());
    }

    @Test
    @DisplayName("BashTool 摘要:run_in_background=true 加 [bg] 标记")
    void bash_summary_bg_prefix() {
        Tool t = new BashTool();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("command", "sleep 100");
        args.put("run_in_background", true);
        assertEquals("[bg] $ sleep 100", t.summary(new ToolCall("bash", args)));
    }

    // ── FileSystemTool ────────────────────────────────────────

    @Test
    @DisplayName("FileSystemTool read_file 摘要:📖 <path>")
    void file_read_summary() {
        Tool t = new FileSystemTool(java.nio.file.Path.of("/tmp"));
        assertEquals("📖 README.md",
                t.summary(new ToolCall("read_file", Map.of("path", "README.md"))));
    }

    @Test
    @DisplayName("FileSystemTool read_file 摘要:limit 附加")
    void file_read_summary_with_limit() {
        Tool t = new FileSystemTool(java.nio.file.Path.of("/tmp"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", "big.log");
        args.put("limit", 50);
        assertEquals("📖 big.log:50",
                t.summary(new ToolCall("read_file", args)));
    }

    @Test
    @DisplayName("FileSystemTool 各子命令摘要 emoji 区分")
    void file_all_subcommands() {
        Tool t = new FileSystemTool(java.nio.file.Path.of("/tmp"));
        assertEquals("💾 out.txt",
                t.summary(new ToolCall("write_file", Map.of("path", "out.txt"))));
        assertEquals("✏️ pom.xml",
                t.summary(new ToolCall("edit_file", Map.of("path", "pom.xml"))));
        assertEquals("🔍 **/*.java",
                t.summary(new ToolCall("glob", Map.of("pattern", "**/*.java"))));
    }

    // ── TaskTool ─────────────────────────────────────────────

    @Test
    @DisplayName("TaskTool 摘要:🧠 task: <描述>")
    void task_summary() {
        Tool t = new TaskTool(null);  // ctor 允许 null subagent(spawn 时才用),summary 不需要
        assertEquals("🧠 task: 分析 X 模块",
                t.summary(new ToolCall("task", Map.of("description", "分析 X 模块"))));
    }

    // ── SessionSearchTool ────────────────────────────────────

    @Test
    @DisplayName("SessionSearchTool 摘要:🔎 \"query\"")
    void search_summary() {
        SessionSearchTool t = new SessionSearchTool(null);  // ctor 允许 null service(summary 不需要)
        assertEquals("🔎 \"user auth bug\"",
                t.summary(new ToolCall("session_search", Map.of("query", "user auth bug"))));
    }

    // ── 内部 stub ─────────────────────────────────────────

    private static class StubTool implements Tool {
        @Override public String getName() { return "stub"; }
        @Override public String getDescription() { return "stub"; }
        @Override public java.util.List<ToolDefinition> getTools() { return java.util.List.of(); }
        @Override public ToolResult execute(ToolCall call) { return new ToolResult(true, ""); }
    }
}
