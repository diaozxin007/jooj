package com.xilidou.jooj.eval.scorer;

import com.xilidou.jooj.eval.GoldenCase;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键词覆盖率:{@code expected} 是逗号分隔的关键词,
 * 得分 = 命中数 / 总数,阈值默认 {@link #THRESHOLD}(0.8)视为通过。
 *
 * <p>典型使用场景:内容完整性检查,如"讲 React 时必提 hooks/JSX/Virtual DOM"。
 * 不再是简单二值——命中大部分也可以拿到部分分,报告更细腻。
 *
 * <p>为什么阈值写死 0.8:先满足 Week11 教学 demo 需求。将来若要按 case 定制,
 * 可以给 {@link GoldenCase} 加一个 optional 的 threshold 字段。
 */
public class KeywordCoverageScorer implements Scorer {

    static final double THRESHOLD = 0.8;

    @Override
    public ScoreResult score(GoldenCase tc, String actual) {
        if (actual == null) return ScoreResult.fail("actual output is null");
        if (tc.expected() == null || tc.expected().isBlank()) {
            return ScoreResult.fail("expected keywords are empty");
        }

        String[] keywords = tc.expected().split(",");
        List<String> hits = new ArrayList<>();
        List<String> misses = new ArrayList<>();
        for (String k : keywords) {
            String kw = k.trim();
            if (kw.isEmpty()) continue;
            if (actual.contains(kw)) hits.add(kw);
            else misses.add(kw);
        }

        int total = hits.size() + misses.size();
        if (total == 0) return ScoreResult.fail("no valid keyword provided");

        double score = (double) hits.size() / total;
        String reason = String.format("hit %d/%d keywords, missing: %s",
                hits.size(), total, misses);
        return ScoreResult.partial(score, THRESHOLD, reason);
    }
}
