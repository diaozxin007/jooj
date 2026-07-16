package com.xilidou.jooj.tui;

import com.xilidou.jooj.channel.InboundDispatcher;
import com.xilidou.jooj.session.Session;
import com.xilidou.jooj.tool.ExecutionContext;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.utils.AttributedStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * TuiCliRunner —— TUI 模式下的**入口层 read loop**(s23 P3 / P5)。
 *
 * <h3>P5 变化(2026-07-16):main thread 只做 readLine + 路由</h3>
 *
 * <p>P3 里 main 直接调 dispatchSync;P5 引入 {@link TuiQueryDispatcher}(queue + worker)后,
 * main 只做**三分支路由**:
 *
 * <ol>
 *   <li><b>有 pending question</b> → 当 answer,调
 *       {@link TuiQueryDispatcher#tryAnswerPending(String)}
 *       (parse 失败提示重试,不入队 —— 避免"想问的问题被吞成 answer")</li>
 *   <li><b>worker 忙 + queue 满</b> → 拒绝并提示"队列已满"</li>
 *   <li><b>否则</b> → 入队,提示"已入队"或"处理中"</li>
 * </ol>
 *
 * <p>这样 main 永远 free(不阻塞在 dispatchSync 里),用户在 agent 挂起等 answer 时能敲键盘。
 *
 * <h3>为什么独立成 CommandLineRunner</h3>
 *
 * <p>TUI 是 jooj 的**唯一活着的主循环**(占管 stdin,阻塞 main thread 直到用户 Ctrl-D 退出)。
 * 如果放 @PostConstruct 起 daemon 线程,daemon 会随 JVM 主线程退出而死。CommandLineRunner
 * 是标准做法。
 *
 * <p>结构与 {@link com.xilidou.jooj.JoojCliRunner} 对称:
 * <ul>
 *   <li>{@code JoojCliRunner @Profile("!test & !web & !tui")} —— legacy CLI 入口</li>
 *   <li>{@code TuiCliRunner   @Profile("tui & !test")} —— TUI CLI 入口</li>
 * </ul>
 *
 * <h3>信号处理</h3>
 *
 * <ul>
 *   <li>{@link UserInterruptException} (Ctrl-C) ——
 *     <ul>
 *       <li>有 pending → 全 deny + 提示</li>
 *       <li>worker 有 in-flight → 请求 agent interrupt</li>
 *       <li>都无 → 只提示 "^C  (Ctrl-D to exit)"</li>
 *     </ul></li>
 *   <li>{@link EndOfFileException} (Ctrl-D / EOF) —— 干净退出</li>
 * </ul>
 */
@Component
@Profile("tui & !test")
public class TuiCliRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TuiCliRunner.class);

    /** JLine LineReader 提示符,复用 legacy CLI 的 "s01 >> " 视觉规范。 */
    private static final String PROMPT = "s01 >> ";

    private final TuiTerminal tui;
    private final TuiQueryDispatcher queryDispatcher;
    private final com.xilidou.jooj.agent.AgentControl agentControl;

    public TuiCliRunner(TuiTerminal tui,
                        TuiQueryDispatcher queryDispatcher,
                        com.xilidou.jooj.agent.AgentControl agentControl) {
        this.tui = tui;
        this.queryDispatcher = queryDispatcher;
        this.agentControl = agentControl;
    }

    @Override
    public void run(String... args) {
        printBanner();

        while (true) {
            String line;
            try {
                line = tui.readLine(PROMPT);
            } catch (UserInterruptException e) {
                handleCtrlC();
                continue;
            } catch (EndOfFileException e) {
                break;
            }

            if (line == null) break;
            String text = line.strip();
            if (text.isEmpty()) continue;
            if (text.equalsIgnoreCase("q") || text.equalsIgnoreCase("exit")) break;

            route(text);
        }

        AttributedStringBuilder bye = new AttributedStringBuilder()
                .style(TuiTerminal.DIM())
                .append("Bye.");
        tui.printlnStyled(bye.toAttributedString());
        log.info("[TUI] runner exiting cleanly");
    }

    /**
     * P5 核心路由:input 走三分支之一。
     */
    private void route(String text) {
        // 分支 1:pending question 优先 —— 用户看到 modal 后下一行是 answer
        TuiQueryDispatcher.AnswerResult ar = queryDispatcher.tryAnswerPending(text);
        switch (ar) {
            case ANSWERED -> {
                AttributedStringBuilder sb = new AttributedStringBuilder()
                        .style(TuiTerminal.DIM())
                        .append("✓ answered");
                tui.printlnStyled(sb.toAttributedString());
                return;
            }
            case PARSE_FAILED -> {
                AttributedStringBuilder sb = new AttributedStringBuilder()
                        .style(TuiTerminal.YELLOW())
                        .append("⚠ 未识别为 answer,请按 modal 格式重试(或输入 /cancel 取消)");
                tui.printlnStyled(sb.toAttributedString());
                return;
            }
            case ASK_ID_STALE, NO_PENDING -> {
                // fall through 到分支 2/3
            }
        }

        // 分支 2:入队
        boolean accepted = queryDispatcher.offer(text);
        if (accepted) {
            int size = queryDispatcher.queueSize();
            String inFlight = queryDispatcher.inFlightQuery();
            if (inFlight != null && !inFlight.isBlank()) {
                AttributedStringBuilder sb = new AttributedStringBuilder()
                        .style(TuiTerminal.DIM())
                        .append("✓ 已入队 (队列:").append(String.valueOf(size))
                        .append(" · 正在处理: ").append(truncate(inFlight, 40)).append(")");
                tui.printlnStyled(sb.toAttributedString());
            }
            // idle 情况不提示 —— worker 立即接手,直接看 renderer 打屏就好
        } else {
            // 分支 3:队列满
            AttributedStringBuilder sb = new AttributedStringBuilder()
                    .style(TuiTerminal.RED())
                    .append("⛔ 队列已满(上限 ").append(String.valueOf(queryDispatcher.queueSize()))
                    .append(" 条),请等 agent 处理完再输入");
            tui.printlnStyled(sb.toAttributedString());
        }
    }

    /**
     * Ctrl-C 语义(D-12):
     * <ol>
     *   <li>有 pending question → 全 deny(反正用户想 abort)</li>
     *   <li>worker 有 in-flight → agentControl.requestInterrupt</li>
     *   <li>都无 → 只提示</li>
     * </ol>
     */
    private void handleCtrlC() {
        int denied = queryDispatcher.denyAllPending("interrupted by Ctrl-C");
        if (denied > 0) {
            AttributedStringBuilder sb = new AttributedStringBuilder()
                    .style(TuiTerminal.YELLOW())
                    .append("⚠ denied ").append(String.valueOf(denied))
                    .append(" pending question(s)");
            tui.printlnStyled(sb.toAttributedString());
            return;
        }
        if (queryDispatcher.inFlightQuery() != null) {
            boolean first = agentControl.requestInterrupt(TuiQueryDispatcher.SESSION_ID);
            AttributedStringBuilder sb = new AttributedStringBuilder()
                    .style(TuiTerminal.YELLOW())
                    .append("^C  interrupt ").append(first ? "requested" : "already requested");
            tui.printlnStyled(sb.toAttributedString());
            return;
        }
        tui.println("^C  (Ctrl-D to exit)");
    }

    private void printBanner() {
        AttributedStringBuilder title = new AttributedStringBuilder()
                .style(TuiTerminal.CYAN())
                .append("jooj TUI (s23 P5)");
        tui.printlnStyled(title.toAttributedString());

        AttributedStringBuilder hint = new AttributedStringBuilder()
                .style(TuiTerminal.DIM())
                .append("输入问题回车发送 · /help 命令 · Ctrl-D 退出 · Ctrl-C 中断 turn/deny pending");
        tui.printlnStyled(hint.toAttributedString());
        tui.println("");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
