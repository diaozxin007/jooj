package com.xilidou.jooj.compact;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Compact 流水线(s08 + s22 D)的 yml → Java 桥接。
 *
 * <p>三分法(参见 [[Jooj项目_配置架构重构_规划]] D-05):
 * <ul>
 *   <li>{@link CompactProperties}(本类)—— {@code @ConfigurationProperties("jooj.compact")},只做 yml 桥接</li>
 *   <li>{@link CompactConfig} —— 运行时 POJO,pipeline 消费的数据结构,可 {@code new} 出来测试</li>
 *   <li>{@link CompactConfiguration} —— {@code @Bean} 装配,把 Properties 拍平成 Config</li>
 * </ul>
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.Compact} 拆出,前缀 {@code jooj.compact} 保持不变。
 */
@Data
@ConfigurationProperties("jooj.compact")
public class CompactProperties {

    private int maxMessages = 50;
    private int snipHeadKeep = 3;
    private int keepRecent = 3;
    private int minPlaceholderLen = 120;
    private int maxToolResultBytes = 10000;
    private int summaryHeadKeep = 3;
    private int summaryTailKeep = 10;
    private int summaryMaxChars = 500;

    /**
     * s22 D 改造:模型的 context 窗口(tokens)。用于 token-aware 压缩触发。
     *
     * <p>常见值:
     * <ul>
     *   <li>Claude 3.5 Sonnet / Opus 4:200_000</li>
     *   <li>GPT-4 Turbo:128_000</li>
     *   <li>本地 32K 模型:32_000</li>
     * </ul>
     *
     * <p>{@code 0} 时禁用 token-aware 触发,退回到旧的"消息数量估计"逻辑。
     */
    private int contextLength = 200_000;

    /**
     * s22 D 改造:token-aware 压缩阈值,占 context 有效输入预算的百分比。
     *
     * <p>触发条件:上一次 API response 里 {@code input_tokens + cache_read_input_tokens}
     * ≥ {@code contextLength * thresholdPercent} 时,下一轮 turn 开始前跑 CompactPipeline。
     *
     * <p>默认 0.70 —— 留 30% 给 output(64K max_tokens 逃逸配额)+ 中途对话增长余量。
     * Hermes 默认 0.50 更激进,jooj 更保守。
     */
    private double thresholdPercent = 0.70;
}
