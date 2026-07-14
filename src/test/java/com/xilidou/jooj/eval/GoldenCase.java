package com.xilidou.jooj.eval;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 一个 Golden Case —— Agent 评测的最小单元。
 *
 * <p>用 record 是因为它天然:
 * <ul>
 *   <li>不可变:加载后不能被测试过程污染</li>
 *   <li>结构清晰:所有字段一览无遗</li>
 *   <li>Jackson 支持好:{@link JsonCreator} + {@link JsonProperty} 让反序列化零配置</li>
 * </ul>
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code id / category} —— 报告聚合、失败追溯</li>
 *   <li>{@code input} —— 喂给 Agent 的输入</li>
 *   <li>{@code expected} —— 期望产出,具体含义由 {@code scorerType} 决定:
 *       EXACT 是原文,REGEX 是正则,KEYWORD_COVERAGE 是逗号分隔关键词,NEGATIVE_MATCH 不用</li>
 *   <li>{@code weight} —— 权重 [0.0, 1.0],体现该用例重要程度</li>
 *   <li>{@code negativePatterns} —— 仅 NEGATIVE_MATCH 使用的黑名单</li>
 * </ul>
 */
public record GoldenCase(
        String id,
        String category,
        String input,
        String expected,
        ScorerType scorerType,
        double weight,
        List<String> negativePatterns
) {

    /** 反序列化入口:允许 category / expected / weight / negativePatterns 缺省。 */
    @JsonCreator
    public static GoldenCase of(
            @JsonProperty("id") String id,
            @JsonProperty("category") String category,
            @JsonProperty("input") String input,
            @JsonProperty("expected") String expected,
            @JsonProperty("scorerType") ScorerType scorerType,
            @JsonProperty("weight") Double weight,
            @JsonProperty("negativePatterns") List<String> negativePatterns
    ) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (input == null) throw new IllegalArgumentException("input required for case " + id);
        if (scorerType == null) throw new IllegalArgumentException("scorerType required for case " + id);
        double w = weight == null ? 1.0 : weight;
        if (w < 0.0 || w > 1.0) {
            throw new IllegalArgumentException("weight must be in [0,1] for case " + id + ", got " + w);
        }
        return new GoldenCase(
                id,
                category == null ? "general" : category,
                input,
                expected == null ? "" : expected,
                scorerType,
                w,
                negativePatterns == null ? List.of() : List.copyOf(negativePatterns)
        );
    }
}
