package com.xilidou.jooj.slashcmd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link SlashCommandRegistry} 的路由行为:
 *
 * <ul>
 *   <li>isCommand: 何时算 slash 命令(/开头, //不算, /单独不算)</li>
 *   <li>dispatch: 拆名 + args, 大小写不敏感, 找不到走 unknown</li>
 *   <li>内置 /help 遍历所有命令</li>
 *   <li>命令抛异常时被 catch 成 "✗ ..." 而不是把异常抛出去</li>
 *   <li>重复注册同名 → 保留第一个,不 crash</li>
 * </ul>
 */
class SlashCommandRegistryTest {

    /** 测试用 fake 命令,记录被调用次数 + 收到的 args。 */
    static class FakeCmd implements SlashCommand {
        final String n;
        final String desc;
        final AtomicInteger calls = new AtomicInteger(0);
        volatile String lastArgs;
        volatile String lastSid;

        /** 若非 null,execute 抛出该异常(测异常路径)。 */
        RuntimeException toThrow;

        FakeCmd(String n) {
            this(n, "desc-" + n);
        }

        FakeCmd(String n, String desc) {
            this.n = n;
            this.desc = desc;
        }

        @Override
        public String name() { return n; }

        @Override
        public String description() { return desc; }

        @Override
        public String execute(String args, String sessionId) {
            calls.incrementAndGet();
            lastArgs = args;
            lastSid = sessionId;
            if (toThrow != null) throw toThrow;
            return "ok:" + n + ":" + args + ":" + sessionId;
        }
    }

    // ────────────────────────────────────────────────────────────
    //  isCommand
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isCommand 判定")
    class IsCommand {

        private final SlashCommandRegistry r = new SlashCommandRegistry(List.of());

        @Test
        @DisplayName("/clear → true")
        void slash_prefix_true() {
            assertTrue(r.isCommand("/clear"));
        }

        @Test
        @DisplayName("带前后空白仍算命令(strip 后判断)")
        void whitespace_trimmed() {
            assertTrue(r.isCommand("  /help  "));
        }

        @Test
        @DisplayName("null → false")
        void null_false() {
            assertFalse(r.isCommand(null));
        }

        @Test
        @DisplayName("空字符串 → false")
        void empty_false() {
            assertFalse(r.isCommand(""));
        }

        @Test
        @DisplayName("不以 / 开头 → false")
        void non_slash_false() {
            assertFalse(r.isCommand("clear"));
            assertFalse(r.isCommand("hello /world"));
        }

        @Test
        @DisplayName("孤零零一个 / → false")
        void bare_slash_false() {
            assertFalse(r.isCommand("/"));
        }

        @Test
        @DisplayName("// 开头 → false(注释/双斜杠不当命令)")
        void double_slash_false() {
            assertFalse(r.isCommand("//comment"));
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Dispatch
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("dispatch 路由")
    class Dispatch {

        @Test
        @DisplayName("/clear → 找到并调用 clear 命令")
        void dispatch_no_args() {
            FakeCmd clear = new FakeCmd("clear");
            SlashCommandRegistry r = new SlashCommandRegistry(List.of(clear));
            String out = r.dispatch("/clear", "sess-1");
            assertEquals(1, clear.calls.get());
            assertEquals("", clear.lastArgs);
            assertEquals("sess-1", clear.lastSid);
            assertTrue(out.startsWith("ok:clear"));
        }

        @Test
        @DisplayName("/memory show foo bar → args=\"show foo bar\"")
        void dispatch_with_args() {
            FakeCmd mem = new FakeCmd("memory");
            SlashCommandRegistry r = new SlashCommandRegistry(List.of(mem));
            r.dispatch("/memory show foo bar", "s1");
            assertEquals("show foo bar", mem.lastArgs);
        }

        @Test
        @DisplayName("命令名大小写不敏感(/CLEAR = /clear)")
        void case_insensitive_lookup() {
            FakeCmd clear = new FakeCmd("clear");
            SlashCommandRegistry r = new SlashCommandRegistry(List.of(clear));
            r.dispatch("/CLEAR", "s1");
            r.dispatch("/Clear", "s1");
            assertEquals(2, clear.calls.get());
        }

        @Test
        @DisplayName("args 保留原始大小写(路径 / 环境变量等敏感)")
        void args_preserve_case() {
            FakeCmd mem = new FakeCmd("memory");
            SlashCommandRegistry r = new SlashCommandRegistry(List.of(mem));
            r.dispatch("/MEMORY Show /Path/To/File", "s1");
            assertEquals("Show /Path/To/File", mem.lastArgs);
        }

        @Test
        @DisplayName("未注册命令 → Unknown command 提示")
        void unknown_command() {
            FakeCmd clear = new FakeCmd("clear");
            SlashCommandRegistry r = new SlashCommandRegistry(List.of(clear));
            String out = r.dispatch("/foo", "s1");
            assertTrue(out.contains("Unknown command"));
            assertTrue(out.contains("/foo"));
            assertTrue(out.contains("/help"), "错误信息应列出 available 含 /help");
            assertTrue(out.contains("/clear"), "错误信息应列出已注册的 /clear");
        }

        @Test
        @DisplayName("dispatch 带前后空白正常工作")
        void trim_input() {
            FakeCmd clear = new FakeCmd("clear");
            SlashCommandRegistry r = new SlashCommandRegistry(List.of(clear));
            r.dispatch("   /clear    ", "s1");
            assertEquals(1, clear.calls.get());
        }

        @Test
        @DisplayName("命令抛异常 → 转成 ✗ 错误文本,不外抛")
        void exception_caught_and_wrapped() {
            FakeCmd broken = new FakeCmd("bomb");
            broken.toThrow = new IllegalStateException("boom!");
            SlashCommandRegistry r = new SlashCommandRegistry(List.of(broken));
            String out = assertDoesNotThrow(() -> r.dispatch("/bomb", "s1"));
            assertTrue(out.startsWith("✗"), "错误文本应以 ✗ 打头,实际=" + out);
            assertTrue(out.contains("/bomb"));
            assertTrue(out.contains("boom!"));
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Built-in /help
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("内置 /help")
    class Help {

        @Test
        @DisplayName("/help 列出所有已注册命令(带 desc)")
        void help_lists_all() {
            SlashCommandRegistry r = new SlashCommandRegistry(List.of(
                    new FakeCmd("clear", "Clear history"),
                    new FakeCmd("memory", "Show memory")));
            String out = r.dispatch("/help", "s1");
            assertTrue(out.contains("/clear"));
            assertTrue(out.contains("Clear history"));
            assertTrue(out.contains("/memory"));
            assertTrue(out.contains("Show memory"));
            assertTrue(out.contains("/help"), "/help 自己也应该列出");
        }

        @Test
        @DisplayName("空 registry 的 /help 至少显示 /help 自己")
        void help_with_no_commands() {
            SlashCommandRegistry r = new SlashCommandRegistry(List.of());
            String out = r.dispatch("/help", "s1");
            assertTrue(out.contains("/help"));
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Duplicate registration
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("重复注册同名命令 → 保留第一个,不 crash")
    void duplicate_names_keeps_first() {
        FakeCmd first = new FakeCmd("dup", "first");
        FakeCmd second = new FakeCmd("dup", "second");
        SlashCommandRegistry r = new SlashCommandRegistry(List.of(first, second));

        r.dispatch("/dup", "s1");
        assertEquals(1, first.calls.get(), "第一个注册的应该被保留");
        assertEquals(0, second.calls.get(), "第二个应该被丢弃");
        assertEquals(1, r.registeredNames().size(), "registeredNames 应该只有 1 个 dup");
    }

    @Test
    @DisplayName("registeredNames 不含 help(help 是 built-in)")
    void registered_names_excludes_help() {
        SlashCommandRegistry r = new SlashCommandRegistry(List.of(
                new FakeCmd("clear"),
                new FakeCmd("memory")));
        var names = r.registeredNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("clear"));
        assertTrue(names.contains("memory"));
        assertFalse(names.contains("help"), "help 是 built-in 不进 registeredNames");
    }
}