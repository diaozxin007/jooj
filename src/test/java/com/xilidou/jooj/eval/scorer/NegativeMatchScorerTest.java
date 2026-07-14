package com.xilidou.jooj.eval.scorer;

import com.xilidou.jooj.eval.GoldenCase;
import com.xilidou.jooj.eval.ScorerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 {@link NegativeMatchScorer} 的黑名单命中 / 未命中 / 空配置 / null 输入。
 */
class NegativeMatchScorerTest {

    private final NegativeMatchScorer scorer = new NegativeMatchScorer();

    private static GoldenCase caseOf(List<String> negatives) {
        return new GoldenCase("t1", "hallu", "input", "",
                ScorerType.NEGATIVE_MATCH, 1.0, negatives);
    }

    @Test
    @DisplayName("actual 命中任一黑名单 -> fail,reason 指出命中项")
    void hits_forbidden_pattern() {
        Scorer.ScoreResult r = scorer.score(
                caseOf(List.of("500 万", "根据官方公告")),
                "我们平台有 500 万用户");
        assertFalse(r.passed());
        assertTrue(r.reason().contains("500 万"));
    }

    @Test
    @DisplayName("actual 未命中任何黑名单 -> pass")
    void no_forbidden_pattern_found() {
        Scorer.ScoreResult r = scorer.score(
                caseOf(List.of("500 万", "根据官方公告")),
                "暂无数据授权,无法回答");
        assertTrue(r.passed());
    }

    @Test
    @DisplayName("negativePatterns 为空列表 -> pass(无风险)")
    void empty_negative_list_passes() {
        Scorer.ScoreResult r = scorer.score(caseOf(List.of()), "任何回答");
        assertTrue(r.passed());
    }

    @Test
    @DisplayName("actual 为 null -> fail,不抛异常")
    void null_actual_fails_gracefully() {
        Scorer.ScoreResult r = scorer.score(caseOf(List.of("x")), null);
        assertFalse(r.passed());
    }
}
