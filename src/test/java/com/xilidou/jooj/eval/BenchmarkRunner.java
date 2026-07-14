package com.xilidou.jooj.eval;

import com.xilidou.jooj.eval.scorer.Scorer;
import com.xilidou.jooj.eval.scorer.ScorerRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 批量执行评测的核心类。
 *
 * <p>职责:
 * <ol>
 *   <li>用注入的 {@code agentFn} 拿到 Agent 的实际输出</li>
 *   <li>按 {@link com.xilidou.jooj.eval.ScorerType} 分派到 {@link Scorer} 打分</li>
 *   <li>聚合成 {@link BenchmarkReport}</li>
 * </ol>
 *
 * <p>设计选择:
 * <ul>
 *   <li>{@code agentFn} 用 {@code Function<String,String>}:输入 -> 输出。
 *       Mock/Real Agent 无缝替换。真实项目里可能想升级成 {@code Function<GoldenCase, AgentResult>}
 *       ,携带 traceId / cost / token 等,但那属于 Week11 的可观测任务,不放这里</li>
 *   <li>单线程顺序执行:50 个 case 不需要并发,加并发反而让日志更乱。要并发就套 CompletableFuture</li>
 *   <li>Agent 抛异常时**继续跑**其他 case,把错误记为字符串 {@code <AGENT_ERROR: ...>};
 *       评测流程本身必须稳,不能被一个失败 case 拖垮</li>
 * </ul>
 */
public class BenchmarkRunner {

    private final ScorerRegistry registry;
    private final Function<String, String> agentFn;

    public BenchmarkRunner(Function<String, String> agentFn) {
        this(agentFn, new ScorerRegistry());
    }

    public BenchmarkRunner(Function<String, String> agentFn, ScorerRegistry registry) {
        this.agentFn = agentFn;
        this.registry = registry;
    }

    public BenchmarkReport run(EvalSet evalSet) {
        List<CaseResult> results = new ArrayList<>();
        for (GoldenCase tc : evalSet.cases()) {
            String actual;
            try {
                actual = agentFn.apply(tc.input());
            } catch (Exception ex) {
                actual = "<AGENT_ERROR: " + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ">";
            }
            Scorer scorer = registry.get(tc.scorerType());
            Scorer.ScoreResult sr = scorer.score(tc, actual);
            results.add(new CaseResult(tc, actual, sr));
        }
        return new BenchmarkReport(evalSet.name(), results);
    }

    // ------------------------------------------------------------------
    // 单用例结果
    // ------------------------------------------------------------------
    public record CaseResult(GoldenCase testCase, String actualOutput, Scorer.ScoreResult scoreResult) {

        public boolean passed() { return scoreResult.passed(); }
        public double score()   { return scoreResult.score(); }
        public double weight()  { return testCase.weight(); }
    }

    // ------------------------------------------------------------------
    // 汇总报告
    // ------------------------------------------------------------------
    public static final class BenchmarkReport {

        private final String name;
        private final List<CaseResult> results;
        private final int totalCases;
        private final int passedCases;
        private final double passRate;
        private final double weightedScore;
        private final Map<String, Double> categoryScores;

        public BenchmarkReport(String name, List<CaseResult> results) {
            this.name = name;
            this.results = List.copyOf(results);
            this.totalCases = results.size();
            this.passedCases = (int) results.stream().filter(CaseResult::passed).count();
            this.passRate = totalCases == 0 ? 0.0 : (double) passedCases / totalCases;

            // 加权得分: Σ(score × weight) / Σ(weight)
            double sumW = 0.0, sumSW = 0.0;
            for (CaseResult r : results) {
                sumW  += r.weight();
                sumSW += r.score() * r.weight();
            }
            this.weightedScore = sumW == 0 ? 0.0 : sumSW / sumW;

            this.categoryScores = computeCategoryScores(results);
        }

        private static Map<String, Double> computeCategoryScores(List<CaseResult> rs) {
            Map<String, double[]> agg = new LinkedHashMap<>(); // [sumSW, sumW]
            for (CaseResult r : rs) {
                agg.compute(r.testCase().category(), (k, v) -> {
                    double[] arr = v == null ? new double[]{0, 0} : v;
                    arr[0] += r.score() * r.weight();
                    arr[1] += r.weight();
                    return arr;
                });
            }
            Map<String, Double> out = new LinkedHashMap<>();
            agg.forEach((k, v) -> out.put(k, v[1] == 0 ? 0.0 : v[0] / v[1]));
            return out;
        }

        // ---- accessors ----
        public String name() { return name; }
        public List<CaseResult> results() { return results; }
        public int totalCases() { return totalCases; }
        public int passedCases() { return passedCases; }
        public double passRate() { return passRate; }
        public double weightedScore() { return weightedScore; }
        public Map<String, Double> categoryScores() { return categoryScores; }

        /** 打印一份人类可读报告。用于 demo 与 CI 日志。 */
        public String render() {
            StringBuilder sb = new StringBuilder();
            String bar = "=".repeat(60);
            sb.append(bar).append("\n");
            sb.append("[Report] ").append(name).append("\n");
            sb.append(bar).append("\n");
            sb.append(String.format("Total: %d | Passed: %d | PassRate: %.1f%%%n",
                    totalCases, passedCases, passRate * 100));
            sb.append(String.format("Weighted score: %.3f%n", weightedScore));
            sb.append("\nCategory:\n");
            categoryScores.forEach((cat, s) ->
                    sb.append(String.format("  - %-25s : %.3f%n", cat, s)));

            sb.append("\nFailures:\n");
            long failed = results.stream().filter(r -> !r.passed()).count();
            if (failed == 0) {
                sb.append("  (none)\n");
            } else {
                results.stream().filter(r -> !r.passed()).forEach(r ->
                    sb.append(String.format("  x %s [%s] %s%n",
                        r.testCase().id(), r.testCase().scorerType(), r.scoreResult().reason())));
            }
            return sb.toString();
        }
    }
}
