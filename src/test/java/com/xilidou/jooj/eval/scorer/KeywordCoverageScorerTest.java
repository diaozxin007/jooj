package com.xilidou.jooj.eval.scorer;

import com.xilidou.jooj.eval.GoldenCase;
import com.xilidou.jooj.eval.ScorerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 {@link KeywordCoverageScorer} 的命中率、阈值边界、异常输入。
 */
class KeywordCoverageScorerTest {

    private final KeywordCoverageScorer scorer = new KeywordCoverageScorer();

    private static GoldenCase caseOf(String keywords) {
        return new GoldenCase("t1", "content", "input", keywords,
                ScorerType.KEYWORD_COVERAGE, 1.0, List.of());
    }

    @Test
    @DisplayName("命中全部 -> score=1.0,passed=true")
    void all_hit() {
        Scorer.ScoreResult r = scorer.score(
                caseOf("JVM,GC,堆,栈"),
                "JVM 内存管理:堆存对象、栈存方法调用、GC 回收");
        assertEquals(1.0, r.score());
        assertTrue(r.passed());
    }

    @Test
    @DisplayName("命中 3/4=0.75 < 0.8 -> passed=false,但携带部分得分")
    void partial_below_threshold() {
        Scorer.ScoreResult r = scorer.score(
                caseOf("JVM,GC,堆,栈"),
                "JVM 内存管理:堆存对象、GC 回收"); // 缺"栈"
        assertEquals(0.75, r.score(), 0.001);
        assertFalse(r.passed());
        assertTrue(r.reason().contains("栈"));
    }

    @Test
    @DisplayName("命中 4/5=0.8 恰好达阈值 -> passed=true")
    void exactly_meets_threshold() {
        Scorer.ScoreResult r = scorer.score(
                caseOf("a,b,c,d,e"),
                "a b c d");
        assertEquals(0.8, r.score(), 0.001);
        assertTrue(r.passed());
    }

    @Test
    @DisplayName("expected 为空 -> fail")
    void empty_expected() {
        Scorer.ScoreResult r = scorer.score(caseOf(""), "anything");
        assertFalse(r.passed());
    }

    @Test
    @DisplayName("空白关键词被跳过,不影响总数")
    void blank_keyword_skipped() {
        // 3 个 keyword,其中一个是空,实际有效为 2 个,命中 2 -> 1.0
        Scorer.ScoreResult r = scorer.score(caseOf("a, ,b"), "a b");
        assertEquals(1.0, r.score(), 0.001);
    }
}
