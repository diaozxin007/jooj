package com.xilidou.jooj.tui;

import com.xilidou.jooj.agent.TurnEvent;
import com.xilidou.jooj.agent.TurnEventPushed;
import com.xilidou.jooj.transcript.AssistantResponseCompleted;
import com.xilidou.jooj.transcript.TurnInterrupted;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TuiTurnRenderer 单元测试(s23 P4)—— 用 mock {@link TuiTerminal} 隔离 JLine,
 * 验证 3 个 EventListener 各自的渲染行为。
 *
 * <h3>为什么 unit 而不是 IT</h3>
 *
 * <p>装配 IT({@link TuiProfileAssemblyTest})已经验证 renderer bean 装配成功;
 * 具体渲染逻辑走 unit 更快、更容易断言"到底打了什么"。renderer 内部只调 tui.println /
 * tui.printlnStyled 两个方法,用 Mockito 抓 argument 即可。
 */
@DisplayName("TuiTurnRenderer 渲染行为 (s23 P4)")
class TuiTurnRendererTest {

    private TuiTerminal tui;
    private TuiTurnRenderer renderer;

    @BeforeEach
    void setUp() {
        tui = Mockito.mock(TuiTerminal.class);
        renderer = new TuiTurnRenderer(tui);
    }

    // ─────────────────────────────────────────────────────────────
    //  TurnEventPushed
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tool_start 事件走 printlnStyled,内容包含 summary")
    void onTurnEvent_toolStart_prints() {
        TurnEvent evt = new TurnEvent(1L, Instant.now(), "tool_start", "$ ls -la");
        renderer.onTurnEvent(new TurnEventPushed("sid1", evt));

        ArgumentCaptor<AttributedString> cap = ArgumentCaptor.forClass(AttributedString.class);
        Mockito.verify(tui).printlnStyled(cap.capture());
        // Attributed string 的 toString 是原文
        String rendered = cap.getValue().toString();
        assertThat(rendered).contains("→").contains("$ ls -la");
        Mockito.verifyNoMoreInteractions(tui);
    }

    @Test
    @DisplayName("未知 type 走灰色降级,不吞信息")
    void onTurnEvent_unknownType_degrades() {
        TurnEvent evt = new TurnEvent(2L, Instant.now(), "future_thing", "some data");
        renderer.onTurnEvent(new TurnEventPushed("sid1", evt));

        ArgumentCaptor<AttributedString> cap = ArgumentCaptor.forClass(AttributedString.class);
        Mockito.verify(tui).printlnStyled(cap.capture());
        String rendered = cap.getValue().toString();
        // 带 [future_thing] 前缀,保留 type 和 summary,不静默丢
        assertThat(rendered).contains("future_thing").contains("some data");
    }

    @Test
    @DisplayName("null summary 不崩(有些 tool 可能 summary 返 null)")
    void onTurnEvent_nullSummary_survives() {
        TurnEvent evt = new TurnEvent(3L, Instant.now(), "tool_start", null);
        // 不抛就 OK
        renderer.onTurnEvent(new TurnEventPushed("sid1", evt));
        Mockito.verify(tui).printlnStyled(Mockito.any(AttributedString.class));
    }

    @Test
    @DisplayName("底层 println 抛异常不冒泡(D1:listener 不能挂主 loop)")
    void onTurnEvent_terminalThrows_doesNotBubble() {
        Mockito.doThrow(new RuntimeException("terminal died"))
                .when(tui).printlnStyled(Mockito.any(AttributedString.class));
        TurnEvent evt = new TurnEvent(4L, Instant.now(), "tool_start", "x");
        // 不抛就 OK
        renderer.onTurnEvent(new TurnEventPushed("sid1", evt));
    }

    // ─────────────────────────────────────────────────────────────
    //  AssistantResponseCompleted
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("assistant 主体走 tui.println(不加 style,白色默认色)")
    void onAssistantResponse_prints_content() {
        renderer.onAssistantResponse(new AssistantResponseCompleted(
                UUID.randomUUID(), "sid1", "Here is your answer.", Instant.now()));
        Mockito.verify(tui).println("Here is your answer.");
        Mockito.verifyNoMoreInteractions(tui);
    }

    @Test
    @DisplayName("blank content 跳过,不 println 空行")
    void onAssistantResponse_blank_skipped() {
        renderer.onAssistantResponse(new AssistantResponseCompleted(
                UUID.randomUUID(), "sid1", "", Instant.now()));
        Mockito.verifyNoInteractions(tui);

        renderer.onAssistantResponse(new AssistantResponseCompleted(
                UUID.randomUUID(), "sid1", "   ", Instant.now()));
        Mockito.verifyNoInteractions(tui);

        renderer.onAssistantResponse(new AssistantResponseCompleted(
                UUID.randomUUID(), "sid1", null, Instant.now()));
        Mockito.verifyNoInteractions(tui);
    }

    @Test
    @DisplayName("assistant println 抛异常不冒泡")
    void onAssistantResponse_terminalThrows_doesNotBubble() {
        Mockito.doThrow(new RuntimeException("boom")).when(tui).println(Mockito.anyString());
        renderer.onAssistantResponse(new AssistantResponseCompleted(
                UUID.randomUUID(), "sid1", "content", Instant.now()));
    }

    // ─────────────────────────────────────────────────────────────
    //  TurnInterrupted
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("中断有 partial:先灰色 partial,再黄色 ⚠ 标记")
    void onTurnInterrupted_partial_prints_partial_then_marker() {
        renderer.onTurnInterrupted(new TurnInterrupted(
                UUID.randomUUID(), "sid1", "I was thinking...", Instant.now()));

        ArgumentCaptor<AttributedString> cap = ArgumentCaptor.forClass(AttributedString.class);
        Mockito.verify(tui, Mockito.times(2)).printlnStyled(cap.capture());
        var all = cap.getAllValues();
        assertThat(all.get(0).toString()).contains("I was thinking...");
        assertThat(all.get(1).toString()).contains("⚠").contains("interrupted");
    }

    @Test
    @DisplayName("中断无 partial:只打 ⚠ 标记")
    void onTurnInterrupted_noPartial_marker_only() {
        renderer.onTurnInterrupted(new TurnInterrupted(
                UUID.randomUUID(), "sid1", "", Instant.now()));

        ArgumentCaptor<AttributedString> cap = ArgumentCaptor.forClass(AttributedString.class);
        Mockito.verify(tui).printlnStyled(cap.capture());
        assertThat(cap.getValue().toString()).contains("⚠").contains("interrupted");
    }

    @Test
    @DisplayName("中断 println 抛异常不冒泡")
    void onTurnInterrupted_terminalThrows_doesNotBubble() {
        Mockito.doThrow(new RuntimeException("boom"))
                .when(tui).printlnStyled(Mockito.any(AttributedString.class));
        renderer.onTurnInterrupted(new TurnInterrupted(
                UUID.randomUUID(), "sid1", "partial", Instant.now()));
    }
}
