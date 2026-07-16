package com.xilidou.jooj.tui;

import com.xilidou.jooj.agent.control.ClarifyQuestion;
import com.xilidou.jooj.agent.control.PendingQuestion;
import com.xilidou.jooj.agent.control.PermissionQuestion;
import com.xilidou.jooj.session.Session;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TuiAnswerPresenter 单元测试(s23 P5)。
 *
 * <p>用 mock {@link TuiTerminal} 隔离 JLine,验证 supports 判断 + 3 态 modal 渲染。
 */
@DisplayName("TuiAnswerPresenter 渲染 (s23 P5)")
class TuiAnswerPresenterTest {

    private TuiTerminal tui;
    private TuiAnswerPresenter presenter;

    @BeforeEach
    void setUp() {
        tui = Mockito.mock(TuiTerminal.class);
        presenter = new TuiAnswerPresenter(tui);
    }

    // ─────────────────────────────────────────────────────────────
    //  supports()
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("supports:originChannel=tui → true")
    void supports_by_channel() {
        PermissionQuestion pq = new PermissionQuestion(
                "a", Instant.now(), "bash", "{}", "r", "tui", "local");
        assertThat(presenter.supports("any-sid", pq)).isTrue();
    }

    @Test
    @DisplayName("supports:sid = CLI_DEFAULT_ID → true")
    void supports_by_cli_default_sid() {
        PermissionQuestion pq = new PermissionQuestion(
                "a", Instant.now(), "bash", "{}", "r", null, null);
        assertThat(presenter.supports(Session.CLI_DEFAULT_ID, pq)).isTrue();
    }

    @Test
    @DisplayName("supports:sid 空 → true(fallback)")
    void supports_by_blank_sid() {
        PermissionQuestion pq = new PermissionQuestion(
                "a", Instant.now(), "bash", "{}", "r", null, null);
        assertThat(presenter.supports(null, pq)).isTrue();
        assertThat(presenter.supports("", pq)).isTrue();
        assertThat(presenter.supports("  ", pq)).isTrue();
    }

    @Test
    @DisplayName("supports:weixin session → false(不越权)")
    void supports_rejects_other_channel() {
        PermissionQuestion pq = new PermissionQuestion(
                "a", Instant.now(), "bash", "{}", "r", "weixin", "user123");
        assertThat(presenter.supports("chat_weixin_user123", pq)).isFalse();
    }

    @Test
    @DisplayName("supports:null question → false")
    void supports_null_question() {
        assertThat(presenter.supports(Session.CLI_DEFAULT_ID, null)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────
    //  present:Permission
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Permission modal 打屏,包含工具名 / 参数 / a/d 提示")
    void present_permission() {
        PermissionQuestion pq = new PermissionQuestion(
                "askid-1", Instant.now(), "bash",
                "{\"command\":\"rm -rf ./build\"}",
                "Rule Gate matched destructive",
                "tui", "local");
        presenter.present(Session.CLI_DEFAULT_ID, pq);

        // 收集所有 println 调用(println + printlnStyled)
        ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
        Mockito.verify(tui, Mockito.atLeastOnce()).println(plain.capture());
        ArgumentCaptor<AttributedString> styled = ArgumentCaptor.forClass(AttributedString.class);
        Mockito.verify(tui, Mockito.atLeastOnce()).printlnStyled(styled.capture());

        String all = String.join("\n", plain.getAllValues())
                + "\n"
                + styled.getAllValues().stream().map(AttributedString::toString)
                        .reduce("", (a, b) -> a + "\n" + b);
        assertThat(all).contains("bash");
        assertThat(all).contains("rm -rf ./build");
        assertThat(all).contains("Rule Gate matched destructive");
        assertThat(all).contains("[a]llow");
        assertThat(all).contains("[d]eny");
    }

    // ─────────────────────────────────────────────────────────────
    //  present:Clarify
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Clarify 单题 modal 打屏,包含 header / question / A/B 选项 / Other")
    void present_clarify_single_question() {
        ClarifyQuestion.SubQuestion sub = new ClarifyQuestion.SubQuestion(
                "选哪个技术栈?", "技术栈",
                List.of(
                        new ClarifyQuestion.Option("Java", "Spring Boot"),
                        new ClarifyQuestion.Option("Python", "FastAPI")),
                false);
        ClarifyQuestion cq = new ClarifyQuestion("askid-c1", Instant.now(),
                List.of(sub), "tui", "local");
        presenter.present(Session.CLI_DEFAULT_ID, cq);

        ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
        Mockito.verify(tui, Mockito.atLeastOnce()).println(plain.capture());
        ArgumentCaptor<AttributedString> styled = ArgumentCaptor.forClass(AttributedString.class);
        Mockito.verify(tui, Mockito.atLeastOnce()).printlnStyled(styled.capture());

        String all = String.join("\n", plain.getAllValues())
                + "\n"
                + styled.getAllValues().stream().map(AttributedString::toString)
                        .reduce("", (a, b) -> a + "\n" + b);
        assertThat(all).contains("技术栈");
        assertThat(all).contains("选哪个技术栈?");
        assertThat(all).contains("A. Java");
        assertThat(all).contains("B. Python");
        assertThat(all).contains("C. Other");        // options 2 → Other 位 = C
        assertThat(all).contains("Spring Boot");     // description
        // 单题格式提示
        assertThat(all).contains("单选");
    }

    @Test
    @DisplayName("Clarify 多题 modal 打屏,含 1/2 · header 和多题格式提示")
    void present_clarify_multi_question() {
        ClarifyQuestion.SubQuestion sub1 = new ClarifyQuestion.SubQuestion(
                "选?", "技术栈",
                List.of(new ClarifyQuestion.Option("Java", null),
                        new ClarifyQuestion.Option("Go", null)),
                false);
        ClarifyQuestion.SubQuestion sub2 = new ClarifyQuestion.SubQuestion(
                "怎么部?", "部署",
                List.of(new ClarifyQuestion.Option("Docker", null),
                        new ClarifyQuestion.Option("K8s", null)),
                true);   // multiSelect
        ClarifyQuestion cq = new ClarifyQuestion("askid-c2", Instant.now(),
                List.of(sub1, sub2), "tui", "local");
        presenter.present(Session.CLI_DEFAULT_ID, cq);

        ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
        Mockito.verify(tui, Mockito.atLeastOnce()).println(plain.capture());
        ArgumentCaptor<AttributedString> styled = ArgumentCaptor.forClass(AttributedString.class);
        Mockito.verify(tui, Mockito.atLeastOnce()).printlnStyled(styled.capture());

        String all = String.join("\n", plain.getAllValues())
                + "\n"
                + styled.getAllValues().stream().map(AttributedString::toString)
                        .reduce("", (a, b) -> a + "\n" + b);
        assertThat(all).contains("1/2");
        assertThat(all).contains("2/2");
        assertThat(all).contains("可多选");             // 第二题 multiSelect 标注
        assertThat(all).contains("多题");               // 尾部格式提示
    }

    // ─────────────────────────────────────────────────────────────
    //  异常不冒泡
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("present 内部抛异常不冒泡(AnswerPresenter 契约)")
    void present_swallows_exceptions() {
        Mockito.doThrow(new RuntimeException("terminal boom"))
                .when(tui).printlnStyled(Mockito.any(AttributedString.class));
        PermissionQuestion pq = new PermissionQuestion(
                "a", Instant.now(), "bash", "{}", "r", "tui", "local");
        // 不抛就 OK
        presenter.present(Session.CLI_DEFAULT_ID, pq);
    }
}
