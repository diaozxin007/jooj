package com.xilidou.jooj.agent.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * s22 AQ:{@link ClarifyQuestion} 参数校验测试。核心场景:
 * <ol>
 *   <li>合法构造</li>
 *   <li>1-4 questions 约束</li>
 *   <li>2-4 options 约束</li>
 *   <li>header ≤12 字符</li>
 *   <li>label 非空</li>
 *   <li>ChoiceAnswer firstSingle 便利方法</li>
 * </ol>
 */
class ClarifyQuestionTest {

    private ClarifyQuestion.SubQuestion sampleSub() {
        return new ClarifyQuestion.SubQuestion(
                "用哪个 UI 库?",
                "UI lib",
                List.of(
                        new ClarifyQuestion.Option("React", "生态最大"),
                        new ClarifyQuestion.Option("Vue", "上手最快")),
                false);
    }

    @Test
    @DisplayName("合法构造:1 个 sub-question,2 options,type=clarify")
    void valid_construction() {
        var q = ClarifyQuestion.of(List.of(sampleSub()));
        assertEquals("clarify", q.type());
        assertNotNull(q.askId());
        assertNotNull(q.askedAt());
        assertEquals(1, q.questions().size());
    }

    @Test
    @DisplayName("questions 空或超过 4 → IAE")
    void questions_size_bounds() {
        assertThrows(IllegalArgumentException.class,
                () -> ClarifyQuestion.of(List.of()));
        var five = List.of(sampleSub(), sampleSub(), sampleSub(), sampleSub(), sampleSub());
        assertThrows(IllegalArgumentException.class, () -> ClarifyQuestion.of(five));
    }

    @Test
    @DisplayName("options 少于 2 或超过 4 → IAE")
    void options_size_bounds() {
        var one = List.of(new ClarifyQuestion.Option("A", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ClarifyQuestion.SubQuestion("q?", "h", one, false));

        var five = List.of(
                new ClarifyQuestion.Option("A", null),
                new ClarifyQuestion.Option("B", null),
                new ClarifyQuestion.Option("C", null),
                new ClarifyQuestion.Option("D", null),
                new ClarifyQuestion.Option("E", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ClarifyQuestion.SubQuestion("q?", "h", five, false));
    }

    @Test
    @DisplayName("header 空或超 12 字符 → IAE")
    void header_length_bound() {
        var opts = List.of(new ClarifyQuestion.Option("A", null), new ClarifyQuestion.Option("B", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ClarifyQuestion.SubQuestion("q?", "", opts, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ClarifyQuestion.SubQuestion("q?", "this-header-is-way-too-long", opts, false));
    }

    @Test
    @DisplayName("question / label 空 → IAE")
    void required_fields() {
        var opts = List.of(new ClarifyQuestion.Option("A", null), new ClarifyQuestion.Option("B", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ClarifyQuestion.SubQuestion("", "h", opts, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ClarifyQuestion.Option("", null));
    }

    @Test
    @DisplayName("ChoiceAnswer.firstSingle:取 index=0 第一个 label")
    void choice_answer_first_single() {
        var ans = new ChoiceAnswer(java.util.Map.of("0", List.of("React"), "1", List.of("Yes")));
        assertEquals("React", ans.firstSingle());

        var empty = new ChoiceAnswer(java.util.Map.of());
        assertNull(empty.firstSingle());
    }
}
