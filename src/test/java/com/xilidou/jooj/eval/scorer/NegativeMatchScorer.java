package com.xilidou.jooj.eval.scorer;

import com.xilidou.jooj.eval.GoldenCase;

/**
 * 反向匹配:{@code negativePatterns} 里任何字符串都不能出现在 {@code actual}。
 *
 * <p>典型使用场景 —— 防幻觉。例如 Agent 不该编造:
 * <ul>
 *   <li>"根据 2024 年 OpenAI 官方公告..."(公告不存在)</li>
 *   <li>"我们平台有 500 万用户"(实际无此数据授权)</li>
 * </ul>
 *
 * <p>权重通常配 1.0 —— 幻觉是最不能容忍的错误类型之一。
 */
public class NegativeMatchScorer implements Scorer {

    @Override
    public ScoreResult score(GoldenCase tc, String actual) {
        if (actual == null) return ScoreResult.fail("actual output is null");
        if (tc.negativePatterns() == null || tc.negativePatterns().isEmpty()) {
            // 未配黑名单等价于无风险,直接通过。
            return ScoreResult.pass("no negative patterns configured");
        }
        for (String bad : tc.negativePatterns()) {
            if (bad != null && !bad.isEmpty() && actual.contains(bad)) {
                return ScoreResult.fail("hallucination detected: contains \"" + bad + "\"");
            }
        }
        return ScoreResult.pass("no forbidden pattern found");
    }
}
