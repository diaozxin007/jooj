package com.xilidou.jooj.eval.scorer;

import com.xilidou.jooj.eval.GoldenCase;

/**
 * 精确匹配:{@code actual.trim()} 必须等于 {@code expected.trim()}。
 *
 * <p>典型使用场景:数据类查询(GMV / DAU / 时差 / HTTP 状态码),
 * 错一个字都不能容忍。权重通常配 1.0。
 */
public class ExactMatchScorer implements Scorer {

    @Override
    public ScoreResult score(GoldenCase tc, String actual) {
        if (actual == null) {
            return ScoreResult.fail("actual output is null");
        }
        String a = actual.trim();
        String e = tc.expected() == null ? "" : tc.expected().trim();
        if (a.equals(e)) {
            return ScoreResult.pass("exact match");
        }
        return ScoreResult.fail(String.format("expected [%s], got [%s]", e, a));
    }
}
