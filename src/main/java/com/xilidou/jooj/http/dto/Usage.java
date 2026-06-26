package com.xilidou.jooj.http.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token 使用统计。
 *
 * <p>JSON 示例：
 * <pre>
 *   {
 *     "input_tokens": 142,
 *     "output_tokens": 47,
 *     "cache_creation_input_tokens": 80,    // 仅 prompt caching 时返回
 *     "cache_read_input_tokens": 30
 *   }
 * </pre>
 *
 * <p>关键设计：
 * <ul>
 *   <li>{@code @JsonIgnoreProperties(ignoreUnknown = true)} - Anthropic 加新字段不会炸</li>
 *   <li>cache 字段用 {@link Integer} 而不是 int - "没用 cache"(null) 和"命中 0 次"(0) 语义不同</li>
 *   <li>主字段用 int - 总会返回，无歧义</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Usage {

    @JsonProperty("input_tokens")
    private int inputTokens;

    @JsonProperty("output_tokens")
    private int outputTokens;

    @JsonProperty("cache_creation_input_tokens")
    private Integer cacheCreationInputTokens;

    @JsonProperty("cache_read_input_tokens")
    private Integer cacheReadInputTokens;

    /**
     * 总 token 数（input + output，不含 cache 相关）。
     */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    /**
     * 估算成本（USD），按 Claude Sonnet 4.6 定价：
     * <ul>
     *   <li>input: $3 / MTok</li>
     *   <li>output: $15 / MTok</li>
     * </ul>
     */
    public double estimatedCostUsd() {
        return (inputTokens * 3.0 + outputTokens * 15.0) / 1_000_000.0;
    }
}
