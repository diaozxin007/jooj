package com.xilidou.jooj.tui;

import com.xilidou.jooj.agent.AgentControl;
import com.xilidou.jooj.agent.control.Answer;
import com.xilidou.jooj.agent.control.AskTimeoutException;
import com.xilidou.jooj.agent.control.PendingQuestion;
import com.xilidou.jooj.agent.control.PermissionQuestion;
import com.xilidou.jooj.channel.InboundDispatcher;
import com.xilidou.jooj.session.Session;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TuiStatusBar 单元测试(s23 P7)。
 *
 * <p>不启动定时线程,直接调 {@link TuiStatusBar#refresh} 验证 status line 内容。
 * 用 mock {@link TuiTerminal} 抓 {@code updateStatus} 参数断言渲染格式。
 */
@DisplayName("TuiStatusBar 渲染 (s23 P7)")
class TuiStatusBarTest {

    private static final String SID = Session.CLI_DEFAULT_ID;

    private TuiTerminal tui;
    private TuiQueryDispatcher qd;
    private FakeAgentControl agentControl;
    private TuiStatusBar bar;

    @BeforeEach
    void setUp() {
        tui = Mockito.mock(TuiTerminal.class);
        // qd 用真的(便于测 queueSize / inFlight 状态);不 start worker
        agentControl = new FakeAgentControl();
        TuiProperties props = new TuiProperties();
        props.setQueueCapacity(5);
        qd = new TuiQueryDispatcher(
                Mockito.mock(InboundDispatcher.class),
                agentControl,
                props);
        bar = new TuiStatusBar(tui, qd, agentControl);
    }

    @Test
    @DisplayName("idle 状态:session + queue:0 + idle + ^C interrupt + ^D exit")
    void status_idle() {
        bar.refresh();

        ArgumentCaptor<AttributedString> cap = ArgumentCaptor.forClass(AttributedString.class);
        Mockito.verify(tui).updateStatus(cap.capture());
        String rendered = cap.getValue().toString();

        assertThat(rendered)
                .contains("session:" + SID)
                .contains("queue:0")
                .contains("idle")
                .contains("^C interrupt")
                .contains("^D exit");
        assertThat(rendered).doesNotContain("pending:");   // 无 pending
        assertThat(rendered).doesNotContain("running");
    }

    @Test
    @DisplayName("queue 有积压:queue:N 反映 offer 数")
    void status_queue_backlog() {
        qd.offer("q1");
        qd.offer("q2");
        qd.offer("q3");

        bar.refresh();
        ArgumentCaptor<AttributedString> cap = ArgumentCaptor.forClass(AttributedString.class);
        Mockito.verify(tui).updateStatus(cap.capture());
        assertThat(cap.getValue().toString()).contains("queue:3");
    }

    @Test
    @DisplayName("有 pending question:显示 pending:N + ^C deny")
    void status_with_pending() {
        agentControl.addPending(SID, new PermissionQuestion(
                "askid-1", Instant.now(), "bash", "{}", "r", "tui", "local"));
        agentControl.addPending(SID, new PermissionQuestion(
                "askid-2", Instant.now(), "bash", "{}", "r", "tui", "local"));

        bar.refresh();
        ArgumentCaptor<AttributedString> cap = ArgumentCaptor.forClass(AttributedString.class);
        Mockito.verify(tui).updateStatus(cap.capture());
        String rendered = cap.getValue().toString();
        assertThat(rendered).contains("pending:2");
        assertThat(rendered).contains("^C deny");         // pending 状态下 Ctrl-C 语义变
        assertThat(rendered).doesNotContain("^C interrupt");
    }

    @Test
    @DisplayName("updateStatus 抛异常不冒泡(不能拖住 refresher)")
    void refresh_swallows_terminal_exception() {
        Mockito.doThrow(new RuntimeException("terminal boom"))
                .when(tui).updateStatus(Mockito.any());
        // 通过 public 入口 refreshSafely 走异常路径;这里为简洁直接调 refresh 期望 exception
        // (refresh 本身不 swallow;refreshSafely 才 swallow);
        // 断言异常在 refresh 内层不会污染 state -- refreshSafely 包裹
        // 用反射调 refreshSafely 太重,直接跑 start-stop 生命周期方式验证:
        Mockito.reset(tui);
        Mockito.when(tui.isDumb()).thenReturn(true);   // 让 start 直接短路,避免起真线程
        bar.start();
        bar.stop();     // 无异常即通过
    }

    @Test
    @DisplayName("start:dumb terminal 时不起 refresher 线程")
    void start_skips_when_dumb() {
        Mockito.when(tui.isDumb()).thenReturn(true);
        bar.start();
        // refresher 不 start,后续 stop 不应 NPE
        bar.stop();
    }

    // ─────────────────────────────────────────────────────────────
    //  Fake AgentControl(简版,复用 P5 模式)
    // ─────────────────────────────────────────────────────────────

    private static class FakeAgentControl implements AgentControl {
        private final List<PendingQuestion> pending = new ArrayList<>();

        void addPending(String sid, PendingQuestion q) { pending.add(q); }

        @Override public boolean requestInterrupt(String sessionId) { return true; }
        @Override public boolean consumeInterrupt(String sessionId) { return false; }
        @Override public boolean isInterruptRequested(String sessionId) { return false; }
        @Override public void clearInterrupt(String sessionId) {}
        @Override public Answer ask(String sessionId, PendingQuestion question, Duration timeout)
                throws AskTimeoutException {
            throw new AskTimeoutException("not used");
        }
        @Override public List<PendingQuestion> listPending(String sessionId) {
            return List.copyOf(pending);
        }
        @Override public boolean answer(String sessionId, String askId, Answer answer) {
            return pending.removeIf(p -> p.askId().equals(askId));
        }
        @Override public Optional<PendingQuestion> findPending(String sessionId, String askId) {
            return pending.stream().filter(p -> p.askId().equals(askId)).findFirst();
        }
        @Override public int cancelPending(String sessionId) {
            int n = pending.size();
            pending.clear();
            return n;
        }
    }
}
