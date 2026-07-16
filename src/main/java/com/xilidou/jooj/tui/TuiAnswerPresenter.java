package com.xilidou.jooj.tui;

import com.xilidou.jooj.agent.control.ClarifyQuestion;
import com.xilidou.jooj.agent.control.PendingQuestion;
import com.xilidou.jooj.agent.control.PermissionQuestion;
import com.xilidou.jooj.channel.AnswerPresenter;
import com.xilidou.jooj.session.Session;
import org.jline.utils.AttributedStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * TuiAnswerPresenter —— TUI 版 {@link AnswerPresenter}(s23 P5)。
 *
 * <h3>职责</h3>
 *
 * <p>只**呈现** {@link PendingQuestion} 给用户,格式化为 modal-style overlay 打屏。
 * **不收 answer** —— 收 answer 走 {@link TuiCliRunner} 主 read loop 里的 pending check 分支。
 *
 * <h3>与 WeixinAnswerPresenter 的对照</h3>
 *
 * <ul>
 *   <li>WeixinAnswerPresenter — 通过 {@code ChannelDeliverer.deliver("weixin", peerId, text)}
 *       发消息;answer 走 IM inbound (WeixinChannel 收消息 → InboundDispatcher.dispatch
 *       → tryAnswerPending)</li>
 *   <li>TuiAnswerPresenter — 通过 {@link TuiTerminal} 直接 println 打屏;
 *       answer 走 TuiCliRunner 下一次 readLine + AnswerParser (P5 新增分支)</li>
 * </ul>
 *
 * <h3>supports() 判断</h3>
 *
 * <p>3 条件任一命中即接管:
 * <ol>
 *   <li>question.originChannel == "tui"</li>
 *   <li>sid == {@link Session#CLI_DEFAULT_ID}(TUI 复用 legacy cli-default session)</li>
 *   <li>sid == null / 空(fallback,处理老代码路径没传 channel meta 的情况)</li>
 * </ol>
 *
 * <p>顺序上 TuiAnswerPresenter 会**优先于** WeixinAnswerPresenter / SSE Presenter —— 因为
 * 只在 tui profile 装配(@Profile("tui")),weixin / web 场景本类根本不存在,不会误吞。
 *
 * <h3>Modal 视觉</h3>
 *
 * <p>见 s23 §5.3。permission 简版一段(工具 / 参数 / 原因 + [a]llow / [d]eny 提示);
 * clarify 版列全部 sub-questions 和 options(A/B/C/D + Other=下一位字母),
 * 尾部提示回复格式(单题 "A" / 多题 "1A 2B" / Other "1E: 详情")。
 */
@Component
@Profile("tui")
public class TuiAnswerPresenter implements AnswerPresenter {

    private static final Logger log = LoggerFactory.getLogger(TuiAnswerPresenter.class);

    /**
     * s23 §5.1 布局 A 里 modal 的边框视觉:上/下用 ─,四角用 ╭╮╰╯,左右用 │。
     * dumb terminal 下 ANSI escape 会被 strip,但 unicode 字符本身保留,视觉不会崩溃。
     */
    private static final String MODAL_TOP    = "╭─────────────────────────────────────────────────╮";
    private static final String MODAL_BOTTOM = "╰─────────────────────────────────────────────────╯";

    private final TuiTerminal tui;

    public TuiAnswerPresenter(TuiTerminal tui) {
        this.tui = tui;
    }

    @Override
    public boolean supports(String sessionId, PendingQuestion question) {
        if (question == null) return false;
        // 1) 明确来自 tui channel
        if ("tui".equals(question.originChannel())) return true;
        // 2) legacy cli-default session(TUI 复用它,legacy REPL 关掉了)
        if (Session.CLI_DEFAULT_ID.equals(sessionId)) return true;
        // 3) sid 为空 fallback(老代码路径)
        return sessionId == null || sessionId.isBlank();
    }

    @Override
    public void present(String sessionId, PendingQuestion question) {
        try {
            if (question instanceof PermissionQuestion pq) {
                renderPermission(pq);
            } else if (question instanceof ClarifyQuestion cq) {
                renderClarify(cq);
            } else {
                // sealed permits 只有 2 种,这个分支理论上到不了;真到了说明扩展了新类型忘记更 renderer
                log.warn("[TUI] unknown PendingQuestion type: {}", question.type());
                renderFallback(question);
            }
        } catch (Throwable t) {
            // Presenter 契约:非阻塞 + 不冒泡异常(异常泄漏会挂 PresenterRegistry loop)
            log.warn("[TUI] present failed for askId={}: {}",
                    question == null ? "?" : question.askId(), t.toString());
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Permission modal
    // ─────────────────────────────────────────────────────────────

    /**
     * Permission modal 布局(s23 §5.3):
     *
     * <pre>
     *   ╭─── Permission ──────────────────────────────────╮
     *   ⚠ Tool:   bash
     *     Input:  {"command":"rm -rf ./build"}
     *     Reason: Rule Gate matched 'destructive'
     *
     *     [a]llow    [d]eny
     *   ╰─────────────────────────────────────────────────╯
     * </pre>
     */
    private void renderPermission(PermissionQuestion pq) {
        tui.println("");
        printBorder(MODAL_TOP, " Permission ");

        AttributedStringBuilder line1 = new AttributedStringBuilder()
                .style(TuiTerminal.YELLOW())
                .append("⚠ Tool:   ").append(pq.toolName());
        tui.printlnStyled(line1.toAttributedString());

        tui.println("  Input:  " + truncate(pq.toolInput(), 400));

        if (pq.reason() != null && !pq.reason().isBlank()) {
            tui.println("  Reason: " + pq.reason());
        }

        tui.println("");
        AttributedStringBuilder prompt = new AttributedStringBuilder()
                .append("  ")
                .style(TuiTerminal.GREEN()).append("[a]llow").style(TuiTerminal.DIM()).append("    ")
                .style(TuiTerminal.RED()).append("[d]eny");
        tui.printlnStyled(prompt.toAttributedString());

        tui.println(MODAL_BOTTOM);
        tui.println("");
    }

    // ─────────────────────────────────────────────────────────────
    //  Clarify modal
    // ─────────────────────────────────────────────────────────────

    /**
     * Clarify modal(s23 §5.3):
     *
     * <pre>
     *   ╭─── Question (2) ────────────────────────────────╮
     *   ▶ [1/2 · 技术栈]  希望使用哪种技术栈?
     *       A. Java — Spring Boot / Maven
     *       B. TypeScript — Node.js
     *       C. Python — FastAPI / Flask
     *       D. Other (自定义)
     *
     *   ▶ [2/2 · 部署方式 · 可多选]  部署方式?
     *       A. Docker
     *       B. Kubernetes
     *       C. Other (自定义)
     *
     *     回复:单题 "A"  多题 "1A 2B"  Other "1D: 详情"
     *   ╰─────────────────────────────────────────────────╯
     * </pre>
     */
    private void renderClarify(ClarifyQuestion cq) {
        int total = cq.questions().size();
        tui.println("");
        printBorder(MODAL_TOP, " Question " + (total > 1 ? "(" + total + ") " : ""));

        for (int qi = 0; qi < total; qi++) {
            ClarifyQuestion.SubQuestion sq = cq.questions().get(qi);
            AttributedStringBuilder head = new AttributedStringBuilder()
                    .style(TuiTerminal.CYAN())
                    .append("▶ ");
            if (total > 1) {
                head.append("[").append(String.valueOf(qi + 1)).append("/")
                        .append(String.valueOf(total)).append(" · ")
                        .append(sq.header());
                if (sq.multiSelect()) head.append(" · 可多选");
                head.append("]  ");
            } else {
                head.append("[").append(sq.header());
                if (sq.multiSelect()) head.append(" · 可多选");
                head.append("]  ");
            }
            head.append(sq.question());
            tui.printlnStyled(head.toAttributedString());

            for (int oi = 0; oi < sq.options().size(); oi++) {
                ClarifyQuestion.Option op = sq.options().get(oi);
                StringBuilder line = new StringBuilder("    ");
                line.append((char) ('A' + oi)).append(". ").append(op.label());
                if (op.description() != null && !op.description().isBlank()) {
                    line.append(" — ").append(op.description());
                }
                tui.println(line.toString());
            }
            // Other 位:字母顺延一位
            tui.println("    " + (char) ('A' + sq.options().size()) + ". Other (自定义)");

            if (qi < total - 1) tui.println("");
        }

        tui.println("");
        AttributedStringBuilder tip = new AttributedStringBuilder()
                .style(TuiTerminal.DIM())
                .append("  回复:");
        if (total == 1) {
            tip.append("单选 \"A\" · 自定义 \"E: 详情\"");
        } else {
            tip.append("多题 \"1A 2B\" · 自定义 \"1E: 详情\"");
        }
        tui.printlnStyled(tip.toAttributedString());

        tui.println(MODAL_BOTTOM);
        tui.println("");
    }

    private void renderFallback(PendingQuestion q) {
        tui.println("");
        printBorder(MODAL_TOP, " " + q.type() + " ");
        tui.println("  " + q.type() + " askId=" + q.askId());
        tui.println("  (未识别的问题类型,请等 timeout 或输入 q 取消 turn)");
        tui.println(MODAL_BOTTOM);
        tui.println("");
    }

    // ─────────────────────────────────────────────────────────────
    //  helpers
    // ─────────────────────────────────────────────────────────────

    /** 把 title 嵌进 top 边框(替换中间几个 ─)。dumb 环境下 unicode 边框依然可读。 */
    private void printBorder(String template, String title) {
        // top 是 ╭──...──╮,把左起第 3 位往后 len(title) 个 ─ 替换成 title
        if (title == null || title.length() >= template.length() - 4) {
            tui.println(template);
            return;
        }
        String head = template.substring(0, 3);
        String middle = title;
        int tailStart = 3 + middle.length();
        String tail = tailStart < template.length()
                ? template.substring(tailStart)
                : "╮";
        AttributedStringBuilder sb = new AttributedStringBuilder()
                .style(TuiTerminal.CYAN())
                .append(head).append(middle).append(tail);
        tui.printlnStyled(sb.toAttributedString());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
