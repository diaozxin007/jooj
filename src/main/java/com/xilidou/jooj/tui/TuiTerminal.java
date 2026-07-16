package com.xilidou.jooj.tui;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * TuiTerminal —— TUI 的**唯一** JLine 依赖点(s23 D-4 架构预留)。
 *
 * <h3>为什么单独抽这一层</h3>
 *
 * <p>s23 §3.6 决策:MVP 走 JLine 3,未来 s24 可能升级到 TamboUI 或 tui4j。为让升级不需要
 * 改 {@code TuiChannel / TuiAnswerPresenter / TuiTurnRenderer},所有 JLine API 都封装在
 * 这个类里,对外只暴露**语义 API**(不是 JLine 特有类型):
 *
 * <ul>
 *   <li>{@link #println(String)} —— 输出一行文本(自动应用 theme 色)</li>
 *   <li>{@link #readLine(String)} —— 提示 + 阻塞读一行</li>
 *   <li>{@link #clearScreen()} —— 清屏</li>
 *   <li>{@link #width()} / {@link #height()} —— 当前终端尺寸</li>
 *   <li>{@link #isDumb()} —— 是否降级为 dumb(pipe / NO_COLOR / CI)</li>
 * </ul>
 *
 * <p>模态输入 / 光标操作 / 状态栏等高级功能在 P4-P7 阶段追加,同样保持只暴露语义 API。
 *
 * <h3>线程安全</h3>
 *
 * <p>JLine 的 {@link Terminal#writer()} 不是线程安全的。
 * 多个 event listener(TurnEventPushed / AssistantResponseCompleted 等)可能并发调
 * {@link #println},本类内部 {@code synchronized} 化保护 writer,防止字节流交织撕裂。
 */
public class TuiTerminal {

    private static final Logger log = LoggerFactory.getLogger(TuiTerminal.class);

    private final Terminal terminal;
    private final LineReader lineReader;
    private final TuiProperties props;
    private final boolean colorEnabled;

    /** JLine {@code TerminalBuilder} 的 "dumb" 类型名,pipe / NO_COLOR / non-tty 时会命中。 */
    private static final String TYPE_DUMB = "dumb";

    public TuiTerminal(Terminal terminal, LineReader lineReader, TuiProperties props) {
        this.terminal = terminal;
        this.lineReader = lineReader;
        this.props = props;
        this.colorEnabled = computeColorEnabled(terminal, props);
        log.info("[TUI] terminal ready type={} size={}x{} colors={} theme={}",
                terminal.getType(), terminal.getWidth(), terminal.getHeight(),
                colorEnabled, props.getTheme());
    }

    /**
     * s23 §5.5:颜色启用逻辑。三条降级路径任一触发即禁色。
     *
     * <ol>
     *   <li>{@code jooj.tui.theme=none} —— 显式关</li>
     *   <li>{@code NO_COLOR} 环境变量存在(遵循 https://no-color.org)</li>
     *   <li>terminal 类型是 dumb(stdout 非 tty,pipe / redirect / CI 场景)</li>
     * </ol>
     */
    private static boolean computeColorEnabled(Terminal terminal, TuiProperties props) {
        if ("none".equalsIgnoreCase(props.getTheme())) return false;
        String noColor = System.getenv("NO_COLOR");
        if (noColor != null && !noColor.isEmpty()) return false;
        if (TYPE_DUMB.equals(terminal.getType())) return false;
        return true;
    }

    /** 是否已降级为 dumb terminal —— 供 renderer 决定是否走增量刷新。 */
    public boolean isDumb() {
        return !colorEnabled;
    }

    /** 当前终端宽度(列数)—— 用于折行 / 表格布局。resize 后动态更新。 */
    public int width() {
        return terminal.getWidth();
    }

    /** 当前终端高度(行数)—— 用于 status bar 定位。resize 后动态更新。 */
    public int height() {
        return terminal.getHeight();
    }

    /**
     * 输出一行文本。**线程安全**,并发调用会串行化,不会交织撕裂。
     *
     * <p>颜色禁用时任何 ANSI escape 会被 JLine 自动 strip。
     */
    public synchronized void println(String text) {
        terminal.writer().println(text == null ? "" : text);
        terminal.writer().flush();
    }

    /**
     * 输出带样式的一行 —— caller 用 {@link AttributedStringBuilder} 构造样式,内部会应用 theme
     * 降级(dumb 时自动 strip)。**线程安全**。
     */
    public synchronized void printlnStyled(AttributedString styled) {
        terminal.writer().println(colorEnabled ? styled.toAnsi(terminal) : styled.toString());
        terminal.writer().flush();
    }

    /**
     * 提示 + 阻塞读一行。
     *
     * <ul>
     *   <li>返 {@code null} —— stdin EOF(用户 Ctrl-D)</li>
     *   <li>返空串 —— 用户直接回车(caller 决定 skip 还是当空 query)</li>
     * </ul>
     *
     * <p>抛 {@link org.jline.reader.UserInterruptException} —— 用户 Ctrl-C(caller 决定 abort / continue)。
     * 抛 {@link org.jline.reader.EndOfFileException} —— stdin close(视同 Ctrl-D 处理)。
     */
    public String readLine(String prompt) {
        return lineReader.readLine(prompt);
    }

    /** 清屏(ANSI clear-screen 序列)。dumb terminal 下 no-op。 */
    public void clearScreen() {
        if (colorEnabled) {
            terminal.puts(org.jline.utils.InfoCmp.Capability.clear_screen);
            terminal.flush();
        }
    }

    /** 关闭底层 JLine terminal。JoojApplication 关停时通过 Spring bean shutdown 调用。 */
    public void close() {
        try {
            terminal.close();
        } catch (IOException e) {
            log.warn("[TUI] terminal close failed: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  常用色彩样式辅助 —— caller 直接用 AttributedStyle.DEFAULT 也行,
    //  但这几个是 s23 §5 里明确用到的语义色,统一命名避免 magic number。
    // ─────────────────────────────────────────────────────────────

    /** 灰色(用户 query 回显 / 次要状态)。 */
    public static AttributedStyle DIM() {
        return AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT + AttributedStyle.BLACK);
    }

    /** 青色(工具调用起始)。 */
    public static AttributedStyle CYAN() {
        return AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
    }

    /** 黄色(警告 / bash cmd)。 */
    public static AttributedStyle YELLOW() {
        return AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
    }

    /** 红色(错误 / blocked)。 */
    public static AttributedStyle RED() {
        return AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
    }

    /** 绿色(成功 / done)。 */
    public static AttributedStyle GREEN() {
        return AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
    }

    /** 便利:打印一组行(每行独立 println,共用 lock)。 */
    public synchronized void printLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        for (String line : lines) {
            terminal.writer().println(line);
        }
        terminal.writer().flush();
    }

    // ─────────────────────────────────────────────────────────────
    //  Status bar(s23 P7)—— 底部固定 1 行,resize 自适应
    // ─────────────────────────────────────────────────────────────

    /**
     * 更新底部 status bar 内容。
     *
     * <p>Dumb terminal / 无色环境下 JLine {@link Status#getStatus(Terminal, boolean)}
     * 返 null(第二参 {@code create=false}) 或返 dummy 实例(第一次调 create=true 时),
     * 我们统一 no-op —— 状态栏在 pipe / CI 环境本来就没意义。
     *
     * <p>**线程安全**:{@code synchronized} 化,防止 refresher 定时任务与主渲染并发撕裂。
     *
     * @param line 底部要显示的一行(带样式);null / 空 → 隐藏 status
     */
    public synchronized void updateStatus(AttributedString line) {
        if (!colorEnabled) return;   // dumb / no-color 下不装 status
        Status status = Status.getStatus(terminal, true);
        if (status == null) return;
        if (line == null || line.length() == 0) {
            status.update(List.of());
        } else {
            status.update(List.of(line));
        }
    }

    /** 清除 status bar(退出时调)。 */
    public synchronized void clearStatus() {
        if (!colorEnabled) return;
        Status status = Status.getStatus(terminal, false);
        if (status != null) status.update(List.of());
    }
}
