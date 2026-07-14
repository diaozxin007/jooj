package com.xilidou.jooj.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 错误恢复阈值(s11)的 yml → Java 桥接。
 *
 * <p>配合 {@link RecoveryCoordinator} 处理 3 类常见错误:
 * <ul>
 *   <li><b>Path 1</b>(max_tokens 截断):{@code defaultMaxTokens} → {@code escalatedMaxTokens}
 *       升级一次,仍截断时通过 continuation prompt 续写,最多 {@code maxRecoveryRetries} 次</li>
 *   <li><b>Path 2</b>(prompt_too_long):reactive compact 一次,不行抛</li>
 *   <li><b>Path 3</b>(429/529 限流过载):指数退避 + 抖动重试 {@code maxRetries} 次;
 *       连续 {@code maxConsecutive529} 次 529 后切换到 {@code fallbackModel}</li>
 * </ul>
 *
 * <p>默认值是生产可用配置。测试通常调小 {@code maxRetries} / {@code baseDelayMs}
 * 让退避测试快速失败。
 *
 * <p>Recovery 没有派生字段/校验,豁免三分法 —— {@link RecoveryCoordinator} 直接消费。
 *
 * <p><b>归属决策</b>(D-06):Recovery 归 {@code agent/} 包,是 AgentLoopHarness 的错误恢复策略,
 * 不单独建 {@code recovery/} 子包。
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.Recovery} 拆出,前缀 {@code jooj.recovery} 保持不变。
 */
@Data
@ConfigurationProperties("jooj.recovery")
public class RecoveryProperties {

    /** 单次 LLM 调用最多重试次数(429/529 走重试路径)。 */
    private int maxRetries = 10;

    /** 退避基数(毫秒),实际延迟 = min(base × 2^attempt, max) + 抖动。 */
    private int baseDelayMs = 500;

    /** 退避封顶(毫秒),防止指数膨胀到分钟级。 */
    private int maxDelayMs = 32_000;

    /** 连续多少次 529 后切 {@code fallbackModel}。0 表示永不切。 */
    private int maxConsecutive529 = 3;

    /**
     * 备胎模型 ID,空字符串 = 不切。
     *
     * <p>典型场景:主模型在用 Sonnet,fallback 配 Haiku 或更便宜的快速模型,
     * 主模型连续过载时降级到 fallback,保持服务可用。
     */
    private String fallbackModel = "";

    /** 主请求默认 max_tokens。 */
    private int defaultMaxTokens = 8000;

    /** Path 1 升级后的 max_tokens(为多数模型的最大输出上限留余量)。 */
    private int escalatedMaxTokens = 64_000;

    /**
     * Path 1 升级后仍截断时,通过 continuation prompt 续写的最多次数。
     * 超过则放弃,返回截断的 assistant 输出 + Fatal 标记。
     */
    private int maxRecoveryRetries = 3;

    /** Path 1 续写时插入的 user prompt。 */
    private String continuationPrompt =
            "Your previous response was cut off. Continue from where you left off.";
}
