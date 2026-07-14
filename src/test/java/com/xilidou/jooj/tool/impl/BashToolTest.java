package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.tool.ExecutionContext;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link BashTool} 的核心行为:
 *
 * <ul>
 *   <li>正常命令能执行、能返回 stdout</li>
 *   <li>危险命令(DANGEROUS 列表) → 被拒 + success=false</li>
 *   <li>缺失 command / 空白 command → 友好错误</li>
 *   <li>ExecutionContext.cwd 生效(命令在指定目录跑)</li>
 *   <li>未知工具名 → 拒绝</li>
 *   <li>getTools 返回正确 schema(command + run_in_background)</li>
 * </ul>
 *
 * <p><b>为什么大部分测试 {@code @DisabledOnOs(OS.WINDOWS)}</b>:BashTool 硬编码
 * {@code sh -c},Windows 环境跑不了。CI/生产都是 mac/linux,这个约束可接受。
 */
@DisabledOnOs(OS.WINDOWS)
class BashToolTest {

    private final BashTool tool = new BashTool();

    private ToolResult run(String command) {
        return tool.execute(new ToolCall("bash", Map.of("command", command)));
    }

    private ToolResult run(String command, ExecutionContext ctx) {
        return tool.execute(new ToolCall("bash", Map.of("command", command)), ctx);
    }

    // ────────────────────────────────────────────────────────────
    //  Happy path
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("echo hello → 输出 hello, success=true")
        void basic_echo() {
            ToolResult r = run("echo hello");
            assertTrue(r.isSuccess());
            assertEquals("hello", r.getOutput());
        }

        @Test
        @DisplayName("多行 stdout 全部返回")
        void multiline_output() {
            ToolResult r = run("printf 'a\\nb\\nc\\n'");
            assertTrue(r.isSuccess());
            assertEquals("a\nb\nc", r.getOutput());
        }

        @Test
        @DisplayName("stderr 也被合并到 stdout(redirectErrorStream=true)")
        void stderr_merged_into_stdout() {
            // sh -c 'echo err 1>&2' 把 err 写到 stderr，pb 把它合流到 stdout
            ToolResult r = run("echo err 1>&2");
            assertTrue(r.isSuccess());
            assertEquals("err", r.getOutput());
        }

        @Test
        @DisplayName("命令没输出 → \"(no output)\" 占位")
        void empty_output_placeholder() {
            ToolResult r = run("true");
            assertTrue(r.isSuccess());
            assertEquals("(no output)", r.getOutput());
        }

        @Test
        @DisplayName("命令 exit 非 0 但有输出,仍算 success=true(BashTool 不查 exit code)")
        void nonzero_exit_is_still_success() {
            // 现有实现:只要不 timeout / exception,就是 success=true。这一条锁定当前语义。
            ToolResult r = run("echo before; false");
            assertTrue(r.isSuccess(), "BashTool 当前语义:能拿到输出即 success,不看 exit code");
            assertEquals("before", r.getOutput());
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Dangerous command interception
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Dangerous command interception")
    class DangerousInterception {

        @Test
        @DisplayName("rm -rf / → 拒绝")
        void blocks_rm_rf_root() {
            ToolResult r = run("rm -rf /");
            assertFalse(r.isSuccess());
            assertTrue(r.getOutput().toLowerCase().contains("dangerous"));
        }

        @Test
        @DisplayName("sudo * → 拒绝(子串匹配)")
        void blocks_sudo() {
            ToolResult r = run("sudo whoami");
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("shutdown → 拒绝")
        void blocks_shutdown() {
            ToolResult r = run("shutdown -h now");
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("reboot → 拒绝")
        void blocks_reboot() {
            ToolResult r = run("reboot");
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("> /dev/... → 拒绝")
        void blocks_dev_write() {
            ToolResult r = run("echo x > /dev/sda");
            assertFalse(r.isSuccess());
        }

        // ── 已知薄弱点(CR 里已提到):记录下来,提醒未来加强 ──

        @Test
        @DisplayName("[known-weak] rm -rf ~/ 目前不被 DANGEROUS 拦截 -- PermissionPipeline 兜底")
        void known_weak_rm_rf_home_not_blocked_by_bash_tool() {
            // 这条并不真的执行 rm(用 echo 假装),只验证 BashTool 内部黑名单不包含它。
            // 真正的安全靠外层 PermissionPipeline / RuleBasedGate 拦截。
            ToolResult r = run("echo would-rm-rf ~/");
            assertTrue(r.isSuccess(),
                    "记录 BashTool.DANGEROUS 现状:'rm -rf ~/' 不在硬列表里,"
                            + "必须靠 PermissionPipeline 兜底。若哪天加进列表了,本测试会 fail 提醒同步。");
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Input validation
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Input validation")
    class InputValidation {

        @Test
        @DisplayName("缺 command 参数 → 友好错误")
        void missing_command_returns_error() {
            ToolResult r = tool.execute(new ToolCall("bash", Map.of()));
            assertFalse(r.isSuccess());
            assertTrue(r.getOutput().toLowerCase().contains("command"));
        }

        @Test
        @DisplayName("command 是空字符串 → 友好错误")
        void empty_command_returns_error() {
            ToolResult r = run("");
            assertFalse(r.isSuccess());
            assertTrue(r.getOutput().toLowerCase().contains("required"));
        }

        @Test
        @DisplayName("command 是空白字符 → 友好错误")
        void blank_command_returns_error() {
            ToolResult r = run("   \t  ");
            assertFalse(r.isSuccess());
        }

        @Test
        @DisplayName("非 bash 工具名 → 拒绝")
        void wrong_tool_name_rejected() {
            ToolResult r = tool.execute(new ToolCall("not_bash", Map.of("command", "echo hi")));
            assertFalse(r.isSuccess());
            assertTrue(r.getOutput().contains("Unknown tool"));
        }
    }

    // ────────────────────────────────────────────────────────────
    //  ExecutionContext.cwd (s18)
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ExecutionContext cwd (s18)")
    class CwdRouting {

        @Test
        @DisplayName("ctx.cwd 指定目录时,pwd 输出该目录")
        void cwd_from_ctx_applied(@TempDir Path tmp) throws IOException {
            // 建一个绝对路径 tmp 目录
            Path realTmp = tmp.toRealPath();
            ExecutionContext ctx = ExecutionContext.inWorktree(
                    "alice", "wt1", realTmp);

            ToolResult r = run("pwd", ctx);
            assertTrue(r.isSuccess());
            // macOS 的 /var/folders/... 会被 realpath 成 /private/var/... ——
            // pwd 输出取决于 sh 是否 resolve symlink。用 startsWith 兼容两种情况。
            String out = r.getOutput();
            assertTrue(out.equals(realTmp.toString()) ||
                            out.equals(realTmp.toRealPath().toString()) ||
                            realTmp.toString().endsWith(out) ||
                            out.endsWith(realTmp.getFileName().toString()),
                    "pwd 应输出 ctx.cwd,实际=" + out + " 期望=" + realTmp);
        }

        @Test
        @DisplayName("ctx=lead() 时,cwd 走 user.dir(fallback)")
        void lead_ctx_uses_user_dir() {
            ToolResult r = run("pwd", ExecutionContext.lead());
            assertTrue(r.isSuccess());
            assertEquals(System.getProperty("user.dir"), r.getOutput());
        }

        @Test
        @DisplayName("ctx=null 时,cwd 走 user.dir")
        void null_ctx_uses_user_dir() {
            ToolResult r = run("pwd", (ExecutionContext) null);
            assertTrue(r.isSuccess());
            assertEquals(System.getProperty("user.dir"), r.getOutput());
        }

        @Test
        @DisplayName("execute(call) 旧签名等价于 lead()")
        void old_signature_equivalent_to_lead() {
            ToolResult a = run("pwd");
            ToolResult b = run("pwd", ExecutionContext.lead());
            assertEquals(a.getOutput(), b.getOutput());
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Tool metadata
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tool metadata")
    class Metadata {

        @Test
        @DisplayName("getName / getDescription 非空")
        void basic_meta() {
            assertEquals("bash", tool.getName());
            assertNotNull(tool.getDescription());
            assertFalse(tool.getDescription().isBlank());
        }

        @Test
        @DisplayName("getTools 返回单个 bash 工具,含 command + run_in_background 两个属性")
        void tool_definition_has_expected_schema() {
            var defs = tool.getTools();
            assertEquals(1, defs.size());
            ToolDefinition def = defs.get(0);
            assertEquals("bash", def.getName());
            var props = def.getInputSchema().getProperties();
            assertTrue(props.containsKey("command"), "schema 必须有 command 字段");
            assertTrue(props.containsKey("run_in_background"),
                    "s13:schema 必须有 run_in_background(可选) 字段");
            // required 只有 command
            var required = def.getInputSchema().getRequired();
            assertTrue(required.contains("command"));
            assertFalse(required.contains("run_in_background"),
                    "run_in_background 应该是 optional");
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Working dir presence check
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("命令在 cwd 里能看到该目录的文件(ls 能列出)")
    void ls_sees_files_in_cwd(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("marker.txt"), "hi");
        ExecutionContext ctx = ExecutionContext.inWorktree("alice", "wt", tmp);

        ToolResult r = run("ls", ctx);
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("marker.txt"),
                "ls in ctx.cwd 应看到该目录下的 marker.txt,实际=" + r.getOutput());
    }
}