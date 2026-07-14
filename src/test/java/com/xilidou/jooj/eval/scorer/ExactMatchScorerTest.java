package com.xilidou.jooj.eval.scorer;

import com.xilidou.jooj.eval.GoldenCase;
import com.xilidou.jooj.eval.ScorerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 {@link ExactMatchScorer} 的 pass / fail / trim / null 分支。
 */
class ExactMatchScorerTest {

    private final ExactMatchScorer scorer = new ExactMatchScorer();

    private static GoldenCase caseOf(String expected) {
        return new GoldenCase("t1", "test", "input", expected,
                ScorerType.EXACT_MATCH, 1.0, List.of());
    }

    @Test
    @DisplayName("完全一致 -> pass")
    void exact_hit() {
        Scorer.ScoreResult r = scorer.score(caseOf("16"), "16");
        assertTrue(r.passed());
        assertEquals(1.0, r.score());
    }

    @Test
    @DisplayName("两侧空白应被 trim,视为一致")
    void trim_both_sides() {
        Scorer.ScoreResult r = scorer.score(caseOf(" 16 "), " 16 \n");
        assertTrue(r.passed());
    }

    @Test
    @DisplayName("内容不同 -> fail,reason 包含期望与实际")
    void mismatch_reports_both_sides() {
        Scorer.ScoreResult r = scorer.score(caseOf("16"), "17");
        assertFalse(r.passed());
        assertTrue(r.reason().contains("16"));
        assertTrue(r.reason().contains("17"));
    }

    @Test
    @DisplayName("actual 为 null -> fail,不抛异常")
    void null_actual_fails_gracefully() {
        Scorer.ScoreResult r = scorer.score(caseOf("16"), null);
        assertFalse(r.passed());
        assertTrue(r.reason().toLowerCase().contains("null"));
    }
}
