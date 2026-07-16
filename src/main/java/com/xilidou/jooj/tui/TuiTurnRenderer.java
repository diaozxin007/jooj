package com.xilidou.jooj.tui;

import com.xilidou.jooj.agent.TurnEventPushed;
import com.xilidou.jooj.transcript.AssistantResponseCompleted;
import com.xilidou.jooj.transcript.TurnInterrupted;
import org.jline.utils.AttributedStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * TuiTurnRenderer —— TUI 的**事件驱动打屏层**(s23 P4)。
 *
 * <h3>职责边界</h3>
 *
 * <p>本类是**只订阅、不发布**的 pure listener。s22 引入的 3 类事件在本类各自转为一行/多行
 * 屏幕输出:
 *
 * <table>
 *   <tr><th>事件</th><th>Publisher</th><th>本类渲染</th></tr>
 *   <tr><td>{@link TurnEventPushed}(type=tool_start)</td>
 *       <td>AgentLoopHarness / Subagent / Teammate 的 pushToolEvent</td>
 *       <td>青色一行 {@code "→ <summary>"}</td></tr>
 *   <tr><td>{@link AssistantResponseCompleted}</td>
 *       <td>AgentLoopHarness.processOneQuery 出口</td>
 *       <td>白色 content(assistant 最终回复主体)</td></tr>
 *   <tr><td>{@link TurnInterrupted}</td>
 *       <td>AgentLoopHarness 捕获 AgentInterruptedException 后</td>
 *       <td>灰色 partial + 黄色 "⚠ 已中断"</td></tr>
 * </table>
 *
 * <h3>与 TuiCliRunner 的分工</h3>
 *
 * <p>P3 阶段 TuiCliRunner 里的 {@code tui.println(reply)} 会被 P4 迁移过来 —— 从
 * "dispatchSync 拿 reply 再打" 变成 "renderer 收到 AssistantResponseCompleted 立刻打"。
 * TuiCliRunner 只保留 read loop + 错误状态提示(busy / agent-failed 等)。
 *
 * <h3>线程模型</h3>
 *
 * <p>Spring {@code @EventListener} 默认是**同步**的 —— publish 时 listener 在 publisher
 * 所在线程立即执行。也就是说本类的方法**跑在 agent 线程**里,不是 TUI 主线程。
 *
 * <p>{@link TuiTerminal#println} / {@link TuiTerminal#printlnStyled} 都是 {@code synchronized}
 * 的(P2 已就位),所以多线程 append 不会撕裂;但 caller 千万不要在 listener 里做**阻塞长
 * I/O** —— 会拖住 processOneQuery 返回。当前实现只做 println 一行,毫秒级完成,安全。
 *
 * <h3>Session 过滤</h3>
 *
 * <p>本类**不做 session 过滤** —— TUI 是本地单用户,只用 {@link com.xilidou.jooj.session.Session#CLI_DEFAULT_ID}
 * 一个 session。web / weixin / cron 触发的事件也会同 publish 到 Spring event bus,但如果它们的
 * sid 不是 cli-default,渲染到 TUI 上其实语义有点错(用户可能困惑 "我没敲这个 query 为什么有回复")。
 *
 * <p>P4 阶段先**不做 session 过滤**,原因:
 * <ol>
 *   <li>MVP 场景下 tui profile + web profile 通常不并存(pidfile 只允许一个 JVM 起 web)</li>
 *   <li>加过滤需要引入 tui 侧的 "current session" 状态,是 P9 侧栏切 session 才需要的能力</li>
 * </ol>
 * P9 stretch 时如果启用侧栏切 session,本类加 sessionId filter。
 */
@Component
@Profile("tui")
public class TuiTurnRenderer {

    private static final Logger log = LoggerFactory.getLogger(TuiTurnRenderer.class);

    private final TuiTerminal tui;

    public TuiTurnRenderer(TuiTerminal tui) {
        this.tui = tui;
    }

    // ─────────────────────────────────────────────────────────────
    //  Turn 内实时事件(工具进度)
    // ─────────────────────────────────────────────────────────────

    /**
     * 消费 {@link TurnEventPushed} —— 目前 source 侧只 push {@code type=tool_start} 一种,
     * 其他类型(text_delta / tool_use_result / thinking)是未来扩展点,遇到时降级为灰色一行。
     *
     * <p>渲染格式:{@code "→ <summary>"},summary 由具体 Tool 提供(如 BashTool → "$ mvn test",
     * FileSystemTool → "📖 src/main/java/..."),已经包含 tool 语义,不需要额外前缀标 tool name。
     */
    @EventListener
    public void onTurnEvent(TurnEventPushed e) {
        try {
            var event = e.event();
            String type = event.type();
            String summary = event.summary() != null ? event.summary() : "";

            switch (type) {
                case "tool_start" -> {
                    AttributedStringBuilder sb = new AttributedStringBuilder()
                            .style(TuiTerminal.CYAN())
                            .append("→ ")
                            .append(summary);
                    tui.printlnStyled(sb.toAttributedString());
                }
                default -> {
                    // 未知/未来 type:降级为灰色一行,不吞掉信息
                    AttributedStringBuilder sb = new AttributedStringBuilder()
                            .style(TuiTerminal.DIM())
                            .append("[")
                            .append(type != null ? type : "?")
                            .append("] ")
                            .append(summary);
                    tui.printlnStyled(sb.toAttributedString());
                }
            }
        } catch (Throwable t) {
            // s22 D1:listener 不能冒泡,内含 try/catch。此处丢日志,不影响 agent loop 继续
            log.warn("[TUI] renderer failed on TurnEvent (session={}, seq={}): {}",
                    e.sessionId(), e.event() == null ? -1 : e.event().seq(), t.toString());
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Turn 收尾:成功 / 中断
    // ─────────────────────────────────────────────────────────────

    /**
     * 消费 {@link AssistantResponseCompleted} —— agent 完整回复完成后打屏。
     *
     * <p>P4 阶段这一路取代 P3 里 TuiCliRunner.processOne 的 println(reply)。event-driven
     * 才是 event-driven 名副其实的实现:CliRunner 不管打屏,只处理 dispatchSync 返回的**错误
     * 状态**(busy / agent-failed 之类,那些**不发**这个事件,只有正常完成才发)。
     */
    @EventListener
    public void onAssistantResponse(AssistantResponseCompleted e) {
        try {
            if (e.content() == null || e.content().isBlank()) {
                log.debug("[TUI] assistant response is blank, skipping render (session={})",
                        e.sessionId());
                return;
            }
            // 白色默认色,不加样式(assistant 主体最应该"低调可读")
            tui.println(e.content());
        } catch (Throwable t) {
            log.warn("[TUI] renderer failed on AssistantResponseCompleted (session={}): {}",
                    e.sessionId(), t.toString());
        }
    }

    /**
     * 消费 {@link TurnInterrupted} —— 用户主动打断 turn 时的兜底渲染。
     *
     * <p>{@code partialContent} 是打断前 lead 已 append 的 assistant 文本片段(可能空)。
     * 展示格式:先灰色打 partial(如果有),再黄色打一个"⚠ 已中断" 系统气泡。
     */
    @EventListener
    public void onTurnInterrupted(TurnInterrupted e) {
        try {
            if (e.partialContent() != null && !e.partialContent().isBlank()) {
                AttributedStringBuilder partial = new AttributedStringBuilder()
                        .style(TuiTerminal.DIM())
                        .append(e.partialContent());
                tui.printlnStyled(partial.toAttributedString());
            }
            AttributedStringBuilder marker = new AttributedStringBuilder()
                    .style(TuiTerminal.YELLOW())
                    .append("⚠ turn interrupted");
            tui.printlnStyled(marker.toAttributedString());
        } catch (Throwable t) {
            log.warn("[TUI] renderer failed on TurnInterrupted (session={}): {}",
                    e.sessionId(), t.toString());
        }
    }
}
