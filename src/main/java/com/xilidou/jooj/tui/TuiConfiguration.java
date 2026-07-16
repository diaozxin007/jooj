package com.xilidou.jooj.tui;

import com.xilidou.jooj.bootstrap.JoojHome;
import com.xilidou.jooj.slashcmd.SlashCommandRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * TUI 域 Spring 装配(s23 P2)。
 *
 * <h3>Profile 门控</h3>
 *
 * <p>整个 {@code tui/} 域用 {@code @Profile("tui")} 门控 —— {@code --spring.profiles.active=tui}
 * 启用时才装配。这样避免:
 *
 * <ul>
 *   <li>legacy CLI(默认 profile 无 tui)启动时不 spawn JLine terminal,不占 stdin</li>
 *   <li>web 模式(spring.profiles.active=web)不启用</li>
 *   <li>测试(spring.profiles.active=test)不启用 —— IT 里如果要测 TUI,单独 @ActiveProfiles("tui")</li>
 * </ul>
 *
 * <p>与 {@code JoojCliRunner @Profile("!test & !web & !tui")} 互斥:tui 启用时 legacy CLI runner
 * 自动禁用,两者不抢 stdin。
 *
 * <h3>Bean 拓扑</h3>
 *
 * <pre>
 *   application.yml.jooj.tui.*
 *        ↓
 *   TuiProperties (@ConfigurationProperties)
 *        ↓
 *   org.jline.Terminal —— 由 TerminalBuilder 自动探测(system 或 dumb fallback)
 *        ↓
 *   org.jline.LineReader —— 基于 Terminal 构造,支持历史 / 补全(P6 追加)
 *        ↓
 *   TuiTerminal —— 语义 API 封装(唯一 JLine 依赖点,见 s23 §3.6 D-4)
 *        ↓
 *   TuiChannel, TuiAnswerPresenter, TuiTurnRenderer (下游 @Component)
 * </pre>
 */
@Configuration
@Profile("tui")
@EnableConfigurationProperties(TuiProperties.class)
public class TuiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TuiConfiguration.class);

    /**
     * 底层 JLine Terminal。
     *
     * <p>{@link TerminalBuilder#system(boolean)} = true:抢管 stdin/stdout,注册 signal handler,
     * 打开 raw mode。CI / 非 tty 环境自动 fallback 到 dumb terminal(能跑但没色 / 快捷键)。
     *
     * <p>{@code encoding=UTF-8} 保证 CJK 字符正常显示;jooj 用户主要是中文对话,这是硬需求。
     *
     * <p>{@code jansi=false} —— macOS/Linux 默认 native pty,不需要 Jansi;
     * Windows 用户走 dumb fallback(s23 D-5:MVP 不做 Windows)。
     */
    @Bean(destroyMethod = "close")
    public Terminal jlineTerminal() throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .encoding(StandardCharsets.UTF_8)
                .jansi(false)
                .build();
        log.info("[TUI] JLine Terminal built: type={} size={}x{}",
                terminal.getType(), terminal.getWidth(), terminal.getHeight());
        return terminal;
    }

    /**
     * LineReader —— 提供 prompt 读取 + 历史 + Tab 补全 + Ctrl-C/Ctrl-D 信号。
     *
     * <h3>P6 变化(2026-07-16)</h3>
     *
     * <p>P2 阶段只装配了裸 LineReader,P6 加三件事:
     *
     * <ol>
     *   <li><b>History 文件持久化</b> —— {@code JoojHome/tui-history} 存 query 历史;
     *       cross-session 保留(重启 TUI 后 ↑/↓ 还能翻到上次的输入)。
     *       ↑/↓ 由 JLine 默认 KeyMap 提供,无需额外绑定。</li>
     *   <li><b>Tab 补全</b> —— 用户输入 {@code /} 后按 Tab,列出所有已注册 slash 命令
     *       ({@link SlashCommandRegistry#registeredNames()} + 内置 {@code /help})。</li>
     *   <li><b>Ctrl-L 清屏</b> —— JLine 默认已绑到 {@code clear-screen} widget,
     *       无需额外配置。</li>
     * </ol>
     *
     * <p>{@code Ctrl-C} 抛 {@link org.jline.reader.UserInterruptException},
     * {@code Ctrl-D} 抛 {@link org.jline.reader.EndOfFileException} —— 都由
     * {@link TuiCliRunner#handleCtrlC} / 主 loop 处理。
     */
    @Bean
    public LineReader jlineLineReader(Terminal terminal,
                                      SlashCommandRegistry slashCommands) {
        Completer completer = new SlashCommandCompleter(slashCommands);
        Path historyPath = JoojHome.getHomePath().resolve("tui-history");

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("jooj")
                .parser(new DefaultParser())
                .completer(completer)
                .history(new DefaultHistory())
                .variable(LineReader.HISTORY_FILE, historyPath)
                .variable(LineReader.HISTORY_SIZE, 1000)
                .variable(LineReader.HISTORY_FILE_SIZE, 5000)
                // Tab 补全启用 case-insensitive,让用户不用担心 /CLEAR 还是 /clear
                .option(LineReader.Option.CASE_INSENSITIVE, true)
                // 单次匹配自动补全,不用二次 Tab
                .option(LineReader.Option.AUTO_LIST, true)
                .build();

        log.info("[TUI] LineReader ready, history file={}, completer={}",
                historyPath, completer.getClass().getSimpleName());
        return reader;
    }

    /**
     * SlashCommandCompleter —— 用户输入 {@code /} 时列出所有已注册命令(含 {@code /help})。
     *
     * <p>触发条件:光标所在 word 以 {@code /} 开头。这样 free-text query 里出现 {@code /}
     * (如 URL {@code https://example.com})不会误触发 —— 只有**行首**的 {@code /} 会补全。
     *
     * <p>候选项从 {@link SlashCommandRegistry#registeredNames} 动态取,新加命令零改动就能被补全。
     */
    static class SlashCommandCompleter implements Completer {
        private final SlashCommandRegistry registry;

        SlashCommandCompleter(SlashCommandRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            String word = line.word();
            // 只在行首以 / 开头时触发,避免污染 free-text
            // line.wordIndex() == 0 && word.startsWith("/")
            if (line.wordIndex() != 0 || !word.startsWith("/")) return;

            List<String> all = new ArrayList<>();
            all.add("/help");
            Collection<String> names = registry.registeredNames();
            for (String n : names) all.add("/" + n);

            for (String cmd : all) {
                candidates.add(new Candidate(
                        cmd,           // value
                        cmd,           // displ
                        null,          // group
                        null,          // descr(未来加 SlashCommand.description() 显示)
                        null,          // suffix
                        null,          // key
                        true));        // complete
            }
        }
    }

    /** TuiTerminal 语义 API 装配 —— 下游只依赖它,不直接依赖 JLine。 */
    @Bean(destroyMethod = "close")
    public TuiTerminal tuiTerminal(Terminal terminal, LineReader lineReader, TuiProperties props) {
        return new TuiTerminal(terminal, lineReader, props);
    }
}
