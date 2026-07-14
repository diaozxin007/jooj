package com.xilidou.jooj.eval;

import com.xilidou.jooj.eval.scorer.Scorer;
import com.xilidou.jooj.eval.scorer.ScorerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端锁定 {@link BenchmarkRunner}:
 *
 * <p>把 4 种 Scorer 各配一个 case,分别造 v1(全错)/ v2(全对)Mock Agent,
 * 验证:
 * <ul>
 *   <li>通过率 / 加权得分计算正确</li>
 *   <li>Agent 抛异常时评测仍能继续</li>
 *   <li>ScorerRegistry 可注入自定义 Scorer</li>
 *   <li>报告 render 不抛异常且包含关键信息</li>
 * </ul>
 */
class BenchmarkRunnerTest {

    private static final EvalSet SET = new EvalSet("t", List.of(
            GoldenCase.of("d1", "data", "q1", "16", ScorerType.EXACT_MATCH, 1.0, null),
            GoldenCase.of("f1", "fmt", "q2", "^\\d+$", ScorerType.REGEX_MATCH, 0.8, null),
            GoldenCase.of("c1", "content", "q3", "a,b,c", ScorerType.KEYWORD_COVERAGE, 0.9, null),
            GoldenCase.of("h1", "hallu", "q4", "", ScorerType.NEGATIVE_MATCH, 1.0,
                    List.of("500 万"))
    ));

    @Test
    @DisplayName("v2 全对 -> passRate=1.0, weightedScore=1.0")
    void all_pass() {
        Function<String, String> v2 = input -> switch (input) {
            case "q1" -> "16";
            case "q2" -> "42";
            case "q3" -> "a b c";
            case "q4" -> "无该数据";
            default -> "";
        };
        BenchmarkRunner.BenchmarkReport rpt = new BenchmarkRunner(v2).run(SET);
        assertEquals(4, rpt.totalCases());
        assertEquals(4, rpt.passedCases());
        assertEquals(1.0, rpt.passRate());
        assertEquals(1.0, rpt.weightedScore(), 1e-9);
        assertEquals(4, rpt.categoryScores().size());
    }

    @Test
    @DisplayName("v1 全错 -> passRate=0, weightedScore=0,4 类分数都是 0")
    void all_fail() {
        Function<String, String> v1 = input -> switch (input) {
            case "q1" -> "17";                            // EXACT fail
            case "q2" -> "abc";                           // REGEX fail
            case "q3" -> "x y z";                         // KEYWORD 0 hit -> fail
            case "q4" -> "我们有 500 万用户";              // NEGATIVE fail
            default -> "";
        };
        BenchmarkRunner.BenchmarkReport rpt = new BenchmarkRunner(v1).run(SET);
        assertEquals(0, rpt.passedCases());
        assertEquals(0.0, rpt.weightedScore());
        rpt.categoryScores().values().forEach(s -> assertEquals(0.0, s));
    }

    @Test
    @DisplayName("Agent 抛异常 -> 记录 <AGENT_ERROR:...>,评测继续,该 case 视为 fail")
    void agent_throws_does_not_kill_runner() {
        Function<String, String> flaky = input -> {
            if ("q1".equals(input)) throw new RuntimeException("boom");
            return "42"; // 其余匹配 REGEX
        };
        BenchmarkRunner.BenchmarkReport rpt = new BenchmarkRunner(flaky).run(SET);
        assertEquals(4, rpt.totalCases());
        BenchmarkRunner.CaseResult first = rpt.results().get(0);
        assertFalse(first.passed());
        assertTrue(first.actualOutput().contains("AGENT_ERROR"));
    }

    @Test
    @DisplayName("ScorerRegistry.register 可覆盖内置策略")
    void custom_scorer_can_override() {
        ScorerRegistry reg = new ScorerRegistry();
        // 造一个永远通过的 EXACT_MATCH
        reg.register(ScorerType.EXACT_MATCH,
                (tc, actual) -> Scorer.ScoreResult.pass("override"));
        Function<String, String> broken = input -> "错错错"; // 本来 EXACT_MATCH 会 fail
        BenchmarkRunner.BenchmarkReport rpt =
                new BenchmarkRunner(broken, reg).run(new EvalSet("t", List.of(
                        GoldenCase.of("d1", null, "q1", "16", ScorerType.EXACT_MATCH, 1.0, null)
                )));
        assertEquals(1, rpt.passedCases());
    }

    @Test
    @DisplayName("render 不抛异常,含 Weighted score / Failures 关键字")
    void render_contains_key_sections() {
        BenchmarkRunner.BenchmarkReport rpt =
                new BenchmarkRunner(input -> "42").run(SET);
        String txt = rpt.render();
        assertTrue(txt.contains("Weighted score"));
        assertTrue(txt.contains("Failures"));
    }
}
