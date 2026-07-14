package com.xilidou.jooj.eval;

/**
 * 评分策略枚举。
 *
 * <p>新增策略只需三步:
 * <ol>
 *   <li>在此加一个枚举值</li>
 *   <li>在 {@code scorer/} 包下实现 {@link com.xilidou.jooj.eval.scorer.Scorer}</li>
 *   <li>在 {@link com.xilidou.jooj.eval.scorer.ScorerRegistry} 里注册</li>
 * </ol>
 *
 * <p>为什么用枚举而不是字符串:JSON 加载时 {@code ScorerType.valueOf(...)}
 * 会在字段错拼时直接抛异常,提早暴露问题;IDE 也能重构改名。
 */
public enum ScorerType {
    /** 精确匹配:数据类断言(GMV=120 万、HTTP 状态码=429) */
    EXACT_MATCH,

    /** 正则匹配:格式合规(日期、CTA 文案、邮箱等) */
    REGEX_MATCH,

    /** 关键词覆盖:内容完整性(必须包含 A/B/C) */
    KEYWORD_COVERAGE,

    /** 反向匹配:防幻觉(不能出现 X/Y/Z) */
    NEGATIVE_MATCH
}
