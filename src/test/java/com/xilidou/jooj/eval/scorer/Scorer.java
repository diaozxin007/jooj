package com.xilidou.jooj.eval.scorer;

import com.xilidou.jooj.eval.GoldenCase;

/**
 * 评分器接口(Strategy Pattern)。
 *
 * <p>为什么返回 {@link ScoreResult} 而不是 boolean:
 * <ul>
 *   <li>支持部分得分(如 KEYWORD_COVERAGE 命中 3/5 得 0.6 分)</li>
 *   <li>携带 reason 便于报告展示与调试</li>
 * </ul>
 *
 * <p>所有实现类应保证:
 * <ul>
 *   <li>{@code actual == null} 时返回 fail(不抛异常),让评测流程稳定跑完</li>
 *   <li>失败原因的 reason 尽量含"期望"与"实际",直接可看</li>
 * </ul>
 */
public interface Scorer {

    ScoreResult score(GoldenCase testCase, String actualOutput);

    /**
     * 打分结果:得分 + 是否通过 + 原因。record 天然不可变。
     *
     * @param score  0.0 ~ 1.0
     * @param passed 是否算通过。二值:一般 score == 1.0 通过;
     *               KEYWORD_COVERAGE 采用 0.8 阈值。
     * @param reason 通过/失败原因,给报告展示,不建议塞过长内容
     */
    record ScoreResult(double score, boolean passed, String reason) {

        public static ScoreResult pass(String reason) {
            return new ScoreResult(1.0, true, reason);
        }

        public static ScoreResult fail(String reason) {
            return new ScoreResult(0.0, false, reason);
        }

        /** 部分得分:score >= threshold 视为通过。 */
        public static ScoreResult partial(double score, double threshold, String reason) {
            return new ScoreResult(score, score >= threshold, reason);
        }
    }
}
