package com.xilidou.jooj.tui;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TuiTerminal 单元测试(s23 P7 补齐)—— 主要验证 no-color 三条降级路径。
 *
 * <p>不测的东西:{@link TuiTerminal#updateStatus} / {@link TuiTerminal#clearStatus} 走 JLine
 * {@code Status.getStatus} 内部 —— 需要真 terminal 才有意义,已在 IT 覆盖。
 */
@DisplayName("TuiTerminal 降级 (s23 P7)")
class TuiTerminalTest {

    /** 构造 mock Terminal 返 dumb type,writer 走 StringWriter 便于断言输出。 */
    private Terminal dumbTerminal() {
        Terminal t = Mockito.mock(Terminal.class);
        Mockito.when(t.getType()).thenReturn("dumb");
        Mockito.when(t.getWidth()).thenReturn(80);
        Mockito.when(t.getHeight()).thenReturn(24);
        Mockito.when(t.writer()).thenReturn(new PrintWriter(new StringWriter()));
        return t;
    }

    private Terminal ansiTerminal() {
        Terminal t = Mockito.mock(Terminal.class);
        Mockito.when(t.getType()).thenReturn("xterm-256color");
        Mockito.when(t.getWidth()).thenReturn(80);
        Mockito.when(t.getHeight()).thenReturn(24);
        Mockito.when(t.writer()).thenReturn(new PrintWriter(new StringWriter()));
        return t;
    }

    @Test
    @DisplayName("dumb terminal 类型 → isDumb=true(pipe / non-tty 场景)")
    void dumb_terminal_type_disables_color() {
        Terminal t = dumbTerminal();
        LineReader lr = Mockito.mock(LineReader.class);
        TuiProperties props = new TuiProperties();
        // theme=default 但 terminal 是 dumb → 仍降级
        TuiTerminal tui = new TuiTerminal(t, lr, props);
        assertThat(tui.isDumb()).isTrue();
    }

    @Test
    @DisplayName("theme=none 显式关闭颜色")
    void theme_none_disables_color() {
        Terminal t = ansiTerminal();
        LineReader lr = Mockito.mock(LineReader.class);
        TuiProperties props = new TuiProperties();
        props.setTheme("none");
        TuiTerminal tui = new TuiTerminal(t, lr, props);
        assertThat(tui.isDumb()).isTrue();
    }

    @Test
    @DisplayName("正常 xterm + theme=default → 启用颜色")
    void ansi_terminal_default_theme_enables_color() {
        Terminal t = ansiTerminal();
        LineReader lr = Mockito.mock(LineReader.class);
        TuiProperties props = new TuiProperties();       // theme=default
        TuiTerminal tui = new TuiTerminal(t, lr, props);
        assertThat(tui.isDumb()).isFalse();
    }

    @Test
    @DisplayName("width/height 从 Terminal 透传")
    void terminal_dimensions_pass_through() {
        Terminal t = Mockito.mock(Terminal.class);
        Mockito.when(t.getType()).thenReturn("dumb");
        Mockito.when(t.getWidth()).thenReturn(120);
        Mockito.when(t.getHeight()).thenReturn(40);
        Mockito.when(t.writer()).thenReturn(new PrintWriter(new StringWriter()));

        LineReader lr = Mockito.mock(LineReader.class);
        TuiTerminal tui = new TuiTerminal(t, lr, new TuiProperties());

        assertThat(tui.width()).isEqualTo(120);
        assertThat(tui.height()).isEqualTo(40);
    }

    @Test
    @DisplayName("println 打到 terminal.writer(线程安全 -- 内部 synchronized)")
    void println_writes_to_terminal() {
        StringWriter sw = new StringWriter();
        Terminal t = Mockito.mock(Terminal.class);
        Mockito.when(t.getType()).thenReturn("dumb");
        Mockito.when(t.getWidth()).thenReturn(80);
        Mockito.when(t.getHeight()).thenReturn(24);
        Mockito.when(t.writer()).thenReturn(new PrintWriter(sw));

        LineReader lr = Mockito.mock(LineReader.class);
        TuiTerminal tui = new TuiTerminal(t, lr, new TuiProperties());

        tui.println("hello");
        assertThat(sw.toString()).contains("hello");
    }

    @Test
    @DisplayName("readLine 委派给 LineReader")
    void readLine_delegates_to_lineReader() {
        Terminal t = dumbTerminal();
        LineReader lr = Mockito.mock(LineReader.class);
        Mockito.when(lr.readLine("> ")).thenReturn("user typed this");

        TuiTerminal tui = new TuiTerminal(t, lr, new TuiProperties());

        assertThat(tui.readLine("> ")).isEqualTo("user typed this");
    }

    @Test
    @DisplayName("dumb 环境下 updateStatus 是 no-op(不 crash)")
    void updateStatus_noop_on_dumb() {
        Terminal t = dumbTerminal();
        LineReader lr = Mockito.mock(LineReader.class);
        TuiTerminal tui = new TuiTerminal(t, lr, new TuiProperties());

        // 不抛就 OK -- 内部 short-circuit
        tui.updateStatus(null);
        tui.updateStatus(org.jline.utils.AttributedString.EMPTY);
        tui.clearStatus();
    }
}
