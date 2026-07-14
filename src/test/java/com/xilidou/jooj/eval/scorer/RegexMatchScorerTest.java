package com.xilidou.jooj.eval.scorer;

import com.xilidou.jooj.eval.GoldenCase;
import com.xilidou.jooj.eval.ScorerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 {@link RegexMatchScorer} 的匹配、非匹配、坏正则、空表达式路径。
 */
class RegexMatchScorerTest {

    private final RegexMatchScorer scorer = new RegexMatchScorer();

    private static GoldenCase caseOf(String pattern) {
        return new GoldenCase("t1", "fmt", "input", pattern,
                ScorerType.REGEX_MATCH, 1.0, List.of());
    }

    @Test
    @DisplayName("匹配日期格式 YYYY-MM-DD -> pass")
    void matches_date() {
        Scorer.ScoreResult r = scorer.score(caseOf("^\\d{4}-\\d{2}-\\d{2}$"), "2026-07-06");
        assertTrue(r.passed());
    }

    @Test
    @DisplayName("不匹配 -> fail,reason 含正则本体")
    void mismatch() {
        String pattern = "^\\d{4}-\\d{2}-\\d{2}$";
        Scorer.ScoreResult r = scorer.score(caseOf(pattern), "2026/07/06");
        assertFalse(r.passed());
        assertTrue(r.reason().contains(pattern));
    }

    @Test
    @DisplayName("坏正则 -> fail,不抛异常")
    void bad_regex_reports_but_does_not_throw() {
        Scorer.ScoreResult r = scorer.score(caseOf("["), "anything");
        assertFalse(r.passed());
        assertTrue(r.reason().contains("bad regex"));
    }

    @Test
    @DisplayName("空正则 -> fail")
    void empty_regex() {
        Scorer.ScoreResult r = scorer.score(caseOf(""), "anything");
        assertFalse(r.passed());
    }

    @Test
    @DisplayName("多次调用同一正则复用 Pattern 缓存(不炸即可)")
    void reuses_pattern_cache() {
        GoldenCase gc = caseOf("^\\d+$");
        for (int i = 0; i < 5; i++) {
            assertTrue(scorer.score(gc, "42").passed());
        }
    }
}
