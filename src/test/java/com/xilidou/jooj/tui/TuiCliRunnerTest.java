package com.xilidou.jooj.tui;

import com.xilidou.jooj.agent.AgentControl;
import com.xilidou.jooj.channel.InboundDispatcher;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TuiCliRunner 主循环行为(s23 P8 补充)—— 覆盖 §9 里 T1.3 (Ctrl-D 退出) 和
 * Ctrl-C 分支路由。
 *
 * <p>不测的东西:{@code processOne} 的完整 dispatchSync 集成走 profile IT。
 * 这里只关注 **read loop 的信号分支路由**,mock 掉 TuiTerminal.readLine 直接注入信号。
 */
@DisplayName("TuiCliRunner 主循环 (s23 P8)")
class TuiCliRunnerTest {

    private TuiTerminal tui;
    private TuiQueryDispatcher qd;
    private AgentControl agentControl;
    private TuiCliRunner runner;

    @BeforeEach
    void setUp() {
        tui = Mockito.mock(TuiTerminal.class);
        agentControl = Mockito.mock(AgentControl.class);

        // 用真 qd(带 mock inboundDispatcher),不 start worker
        TuiProperties props = new TuiProperties();
        props.setQueueCapacity(3);
        qd = new TuiQueryDispatcher(
                Mockito.mock(InboundDispatcher.class),
                agentControl,
                props);

        runner = new TuiCliRunner(tui, qd, agentControl);
    }

    // ─────────────────────────────────────────────────────────────
    //  T1.3: Ctrl-D 干净退出
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EOF (Ctrl-D) → main loop 干净退出,打 Bye")
    void ctrlD_exits_cleanly() {
        // 第一次 readLine 就抛 EOF
        Mockito.when(tui.readLine(Mockito.anyString()))
                .thenThrow(new EndOfFileException());

        runner.run();
        // 打印了 banner + Bye,但没崩
        Mockito.verify(tui, Mockito.atLeastOnce()).println(Mockito.anyString());
    }

    @Test
    @DisplayName("readLine 返 null → 视同 EOF 退出")
    void null_readLine_exits() {
        Mockito.when(tui.readLine(Mockito.anyString())).thenReturn(null);

        runner.run();
        // 走完 loop,无异常
        Mockito.verify(tui, Mockito.atLeastOnce()).println(Mockito.anyString());
    }

    // ─────────────────────────────────────────────────────────────
    //  Q / EXIT 命令退出
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("输入 'q' → 退出")
    void q_exits() {
        Mockito.when(tui.readLine(Mockito.anyString()))
                .thenReturn("q");
        runner.run();
    }

    @Test
    @DisplayName("输入 'exit' → 退出")
    void exit_exits() {
        Mockito.when(tui.readLine(Mockito.anyString()))
                .thenReturn("exit");
        runner.run();
    }

    @Test
    @DisplayName("空字符串 → continue 到下一轮,不入队")
    void empty_line_skipped() {
        Mockito.when(tui.readLine(Mockito.anyString()))
                .thenReturn("")
                .thenThrow(new EndOfFileException());
        runner.run();
        // 队列没内容
        assertThat(qd.queueSize()).isZero();
    }

    // ─────────────────────────────────────────────────────────────
    //  Ctrl-C 分支路由
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ctrl-C 空闲状态 → 只提示,不退出")
    void ctrlC_idle_prompts() {
        // 第一次 readLine 抛 UserInterrupt,第二次 EOF 让它退出
        Mockito.when(tui.readLine(Mockito.anyString()))
                .thenThrow(new UserInterruptException(""))
                .thenThrow(new EndOfFileException());

        runner.run();
        // 应有 "^C" 提示打屏
        Mockito.verify(tui, Mockito.atLeastOnce()).println(Mockito.contains("^C"));
    }

    @Test
    @DisplayName("Ctrl-C + 有 pending → 走 denyAllPending 分支")
    void ctrlC_with_pending_denies() {
        Mockito.when(agentControl.listPending(Mockito.anyString()))
                .thenReturn(java.util.List.of(
                        new com.xilidou.jooj.agent.control.PermissionQuestion(
                                "askid", java.time.Instant.now(), "bash", "{}", "r", "tui", "local")))
                .thenReturn(java.util.List.of());  // 第二次调用返空(deny 后)
        Mockito.when(agentControl.answer(Mockito.anyString(), Mockito.eq("askid"), Mockito.any()))
                .thenReturn(true);

        Mockito.when(tui.readLine(Mockito.anyString()))
                .thenThrow(new UserInterruptException(""))
                .thenThrow(new EndOfFileException());

        runner.run();

        // deny 被调用
        Mockito.verify(agentControl).answer(Mockito.anyString(), Mockito.eq("askid"),
                Mockito.any(com.xilidou.jooj.agent.control.DenyAnswer.class));
    }

    // ─────────────────────────────────────────────────────────────
    //  入队分支
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("普通 query → 入队,queueSize 涨")
    void normal_query_enqueued() {
        Mockito.when(agentControl.listPending(Mockito.anyString()))
                .thenReturn(java.util.List.of());  // 无 pending
        Mockito.when(tui.readLine(Mockito.anyString()))
                .thenReturn("hello world")
                .thenThrow(new EndOfFileException());

        runner.run();

        assertThat(qd.queueSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("queue 满 → offer 返 false,提示已满")
    void queue_full_prompts() {
        Mockito.when(agentControl.listPending(Mockito.anyString()))
                .thenReturn(java.util.List.of());

        // 塞满(cap=3)
        qd.offer("q1"); qd.offer("q2"); qd.offer("q3");
        assertThat(qd.offer("qN")).isFalse();   // 满了

        Mockito.when(tui.readLine(Mockito.anyString()))
                .thenReturn("would-be-rejected")
                .thenThrow(new EndOfFileException());

        runner.run();

        // 仍是 3,新的没入
        assertThat(qd.queueSize()).isEqualTo(3);
    }
}
