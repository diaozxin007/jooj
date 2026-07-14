package com.xilidou.jooj.eval.scorer;

import com.xilidou.jooj.eval.ScorerType;

import java.util.EnumMap;
import java.util.Map;

/**
 * {@link ScorerType} -> {@link Scorer} 的注册表。
 *
 * <p>作用:让 BenchmarkRunner 不用写 switch,新增策略无需改核心代码。
 *
 * <p>用 {@link EnumMap} 而非 {@code HashMap}:枚举键的最优选择,
 * 内部数组实现,零 hash 冲突,查询 O(1)。
 *
 * <p>不做 {@code @Component}:这是纯数据结构,Week11 的 EvalDemoRunner 是独立 main
 * (类似 s01 的 AgentHarnessDemo),不走 Spring 容器。将来若要 Spring 化,
 * 只需加 {@code @Component} 即可,内部不需要改动。
 */
public class ScorerRegistry {

    private final Map<ScorerType, Scorer> scorers = new EnumMap<>(ScorerType.class);

    /** 默认注册全部 4 种。 */
    public ScorerRegistry() {
        scorers.put(ScorerType.EXACT_MATCH, new ExactMatchScorer());
        scorers.put(ScorerType.REGEX_MATCH, new RegexMatchScorer());
        scorers.put(ScorerType.KEYWORD_COVERAGE, new KeywordCoverageScorer());
        scorers.put(ScorerType.NEGATIVE_MATCH, new NegativeMatchScorer());
    }

    public Scorer get(ScorerType type) {
        Scorer s = scorers.get(type);
        if (s == null) throw new IllegalArgumentException("no scorer registered for " + type);
        return s;
    }

    /** 允许业务方注入自定义策略,如未来的 LlmJudgeScorer / SemanticSimilarityScorer。 */
    public void register(ScorerType type, Scorer scorer) {
        scorers.put(type, scorer);
    }
}
