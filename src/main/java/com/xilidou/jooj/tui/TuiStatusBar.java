package com.xilidou.jooj.tui;

import com.xilidou.jooj.agent.AgentControl;
import com.xilidou.jooj.session.Session;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jline.utils.AttributedStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * TuiStatusBar —— 底部固定 status 行(s23 P7)。
 *
 * <h3>显示格式</h3>
 *
 * <pre>
 *   session:cli-default │ queue:2 │ pending:1 │ ^C interrupt/deny  ^D exit
 * </pre>
 *
 * <p>数据源都是当前**可获取**的真实状态,不是假 metric(tokens/s 等无源数据不放)。
 *
 * <h3>刷新机制</h3>
 *
 * <p>定时 500ms 拉一次状态并调 {@link TuiTerminal#updateStatus} —— 简单可靠,不需要
 * 事件订阅协调。UI 状态从来不是关键路径,500ms 刷新对 human perception 已经足够
 * (人眼对 status bar 变化的敏感度远低于 250ms 阈值)。
 *
 * <h3>降级</h3>
 *
 * <p>Dumb terminal(pipe / NO_COLOR / CI)下 {@link TuiTerminal#updateStatus} 内部
 * short-circuit —— 定时任务照跑但产出 no-op,零副作用。
 */
@Component
@Profile("tui & !test")
public class TuiStatusBar {

    private static final Logger log = LoggerFactory.getLogger(TuiStatusBar.class);

    /** 刷新周期(ms)。500ms 平衡"及时"与"CPU 空转"。 */
    private static final long REFRESH_INTERVAL_MS = 500L;

    private final TuiTerminal tui;
    private final TuiQueryDispatcher queryDispatcher;
    private final AgentControl agentControl;

    private ScheduledExecutorService refresher;

    public TuiStatusBar(TuiTerminal tui,
                        TuiQueryDispatcher queryDispatcher,
                        AgentControl agentControl) {
        this.tui = tui;
        this.queryDispatcher = queryDispatcher;
        this.agentControl = agentControl;
    }

    @PostConstruct
    public void start() {
        if (tui.isDumb()) {
            log.info("[TUI] status bar disabled (dumb terminal)");
            return;
        }
        refresher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tui-status-bar");
            t.setDaemon(true);
            return t;
        });
        refresher.scheduleAtFixedRate(this::refreshSafely,
                REFRESH_INTERVAL_MS, REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("[TUI] status bar started (refresh every {}ms)", REFRESH_INTERVAL_MS);
    }

    @PreDestroy
    public void stop() {
        if (refresher != null) {
            refresher.shutdownNow();
        }
        tui.clearStatus();
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (Throwable t) {
            // Status bar 失败不该扩散到 caller / renderer
            log.debug("[TUI] status refresh failed: {}", t.toString());
        }
    }

    /** 构造 status line 并推给 terminal。可见于 unit test。 */
    void refresh() {
        int queueSize = queryDispatcher.queueSize();
        int pendingCount = agentControl.listPending(Session.CLI_DEFAULT_ID).size();
        String inFlight = queryDispatcher.inFlightQuery();

        AttributedStringBuilder sb = new AttributedStringBuilder()
                .style(TuiTerminal.DIM())
                .append("session:").append(Session.CLI_DEFAULT_ID)
                .append(" │ queue:").append(String.valueOf(queueSize));

        if (inFlight != null) {
            sb.append(" │ ").style(TuiTerminal.CYAN()).append("running").style(TuiTerminal.DIM());
        } else {
            sb.append(" │ idle");
        }

        if (pendingCount > 0) {
            sb.append(" │ ").style(TuiTerminal.YELLOW())
                    .append("pending:").append(String.valueOf(pendingCount))
                    .style(TuiTerminal.DIM());
        }

        sb.append(" │ ^C ").append(pendingCount > 0 ? "deny" : "interrupt").append("  ^D exit");

        tui.updateStatus(sb.toAttributedString());
    }
}
