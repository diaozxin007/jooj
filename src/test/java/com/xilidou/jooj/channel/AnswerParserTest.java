package com.xilidou.jooj.channel;

import com.xilidou.jooj.agent.control.ChoiceAnswer;
import com.xilidou.jooj.agent.control.ClarifyQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * s22 D-12-b:{@link AnswerParser} 单测。覆盖 IM channel 里用户可能回复的
 * 各种自然文本格式,以及**不能识别时降级到 empty**(caller 走普通聊天路径)。
 */
class AnswerParserTest {

    // ── 单题场景 ────────────────────────────────────────────

    private ClarifyQuestion singleQuestion(boolean multi) {
        return ClarifyQuestion.of(List.of(
                new ClarifyQuestion.SubQuestion("Which lang?", "lang",
                        List.of(
                                new ClarifyQuestion.Option("Java", null),
                                new ClarifyQuestion.Option("Python", null),
                                new ClarifyQuestion.Option("Go", null),
                                new ClarifyQuestion.Option("Rust", null)),
                        multi)));
    }

    @Test
    @DisplayName("单题:'A' → Java")
    void single_letter() {
        var r = AnswerParser.tryParse("A", singleQuestion(false));
        assertTrue(r.isPresent());
        assertEquals(List.of("Java"), r.get().selections().get("0"));
    }

    @Test
    @DisplayName("单题:小写 'b' → Python(大小写不敏感)")
    void single_lowercase() {
        var r = AnswerParser.tryParse("b", singleQuestion(false));
        assertTrue(r.isPresent());
        assertEquals(List.of("Python"), r.get().selections().get("0"));
    }

    @Test
    @DisplayName("单题:'其它 Kotlin' → Other: Kotlin")
    void single_other_zh() {
        var r = AnswerParser.tryParse("其它 Kotlin", singleQuestion(false));
        assertTrue(r.isPresent());
        assertEquals(List.of("Other: Kotlin"), r.get().selections().get("0"));
    }

    @Test
    @DisplayName("单题:'Other Scala' → Other: Scala")
    void single_other_en() {
        var r = AnswerParser.tryParse("Other Scala", singleQuestion(false));
        assertTrue(r.isPresent());
        assertEquals(List.of("Other: Scala"), r.get().selections().get("0"));
    }

    @Test
    @DisplayName("单题 multiSelect:'AB' → [Java, Python]")
    void single_multi_letters() {
        var r = AnswerParser.tryParse("AB", singleQuestion(true));
        assertTrue(r.isPresent());
        assertEquals(List.of("Java", "Python"), r.get().selections().get("0"));
    }

    @Test
    @DisplayName("单题非 multi:'AB' 被拒绝(single 不能选多个)")
    void single_non_multi_rejects_multi_letters() {
        var r = AnswerParser.tryParse("AB", singleQuestion(false));
        assertTrue(r.isEmpty(), "single-select 不该接受多字母选择");
    }

    // ── 多题场景 ────────────────────────────────────────────

    private ClarifyQuestion twoQuestions(boolean secondMulti) {
        return ClarifyQuestion.of(List.of(
                new ClarifyQuestion.SubQuestion("Type?", "type",
                        List.of(
                                new ClarifyQuestion.Option("Web", null),
                                new ClarifyQuestion.Option("CLI", null)),
                        false),
                new ClarifyQuestion.SubQuestion("Features?", "feat",
                        List.of(
                                new ClarifyQuestion.Option("auth", null),
                                new ClarifyQuestion.Option("search", null),
                                new ClarifyQuestion.Option("chat", null)),
                        secondMulti)));
    }

    @Test
    @DisplayName("多题:'1A 2B' → Web + search")
    void multi_indexed() {
        var r = AnswerParser.tryParse("1A 2B", twoQuestions(false));
        assertTrue(r.isPresent(), "结果:" + r);
        assertEquals(List.of("Web"), r.get().selections().get("0"));
        assertEquals(List.of("search"), r.get().selections().get("1"));
    }

    @Test
    @DisplayName("多题 multiSelect:'1A 2AC' → Web + [auth, chat]")
    void multi_second_is_multiselect() {
        var r = AnswerParser.tryParse("1A 2AC", twoQuestions(true));
        assertTrue(r.isPresent(), "结果:" + r);
        assertEquals(List.of("Web"), r.get().selections().get("0"));
        assertEquals(List.of("auth", "chat"), r.get().selections().get("1"));
    }

    @Test
    @DisplayName("多题:漏一题 → empty(不许部分答复)")
    void multi_missing_returns_empty() {
        var r = AnswerParser.tryParse("1A", twoQuestions(false));
        assertTrue(r.isEmpty(), "只答 1 题应拒绝,让 caller 降级");
    }

    // ── 非法 / 降级 ────────────────────────────────────────

    @Test
    @DisplayName("越界字母:'X' 单题只 4 option → empty")
    void out_of_bounds() {
        assertTrue(AnswerParser.tryParse("X", singleQuestion(false)).isEmpty());
    }

    @Test
    @DisplayName("完全不像 answer:'我想想再说' → empty")
    void gibberish() {
        assertTrue(AnswerParser.tryParse("我想想再说", singleQuestion(false)).isEmpty());
    }

    @Test
    @DisplayName("空 / null → empty")
    void empty_or_null() {
        assertTrue(AnswerParser.tryParse(null, singleQuestion(false)).isEmpty());
        assertTrue(AnswerParser.tryParse("", singleQuestion(false)).isEmpty());
        assertTrue(AnswerParser.tryParse("   ", singleQuestion(false)).isEmpty());
    }

    @Test
    @DisplayName("Other 位但没自定义文本:'E' 无内容 → empty")
    void other_without_custom_text() {
        var r = AnswerParser.tryParse("1E", singleQuestion(false));
        assertTrue(r.isEmpty(), "选了 Other 但没写内容应拒绝");
    }
}
