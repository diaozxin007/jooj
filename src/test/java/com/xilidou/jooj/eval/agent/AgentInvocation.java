package com.xilidou.jooj.eval.agent;

/**
 * 单次 Agent 调用的完整可观测结果。
 *
 * <p>为什么用 record:
 * <ul>
 *   <li>不可变 —— {@link com.xilidou.jooj.eval.BenchmarkRunner} 拿到后不会再改</li>
 *   <li>字段扁平清晰 —— output / token / 延时 / 错误一览无遗</li>
 *   <li>equals/hashCode 天然可用 —— 测试断言可以直接比对</li>
 * </ul>
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code output}      —— 模型返回的文本(用于 Scorer 打分)。失败时会填
 *       {@code <AGENT_ERROR: ...>} 便于报告展示</li>
 *   <li>{@code inputTokens} —— {@link com.xilidou.jooj.http.dto.Usage#getInputTokens}
 *       。Usage 不存在时以 0 填充,不为 null</li>
 *   <li>{@code outputTokens}—— 同上,{@link com.xilidou.jooj.http.dto.Usage#getOutputTokens}</li>
 *   <li>{@code latencyMs}   —— 从发起到收到响应(或抛异常)的墙钟时间</li>
 *   <li>{@code errorReason} —— {@code null} 表示成功;失败时是异常 message。
 *       用 String 而不是 Throwable 是因为 BenchmarkReport 要序列化/打印,
 *       堆栈不需要跟着传</li>
 * </ul>
 */
public record AgentInvocation(
        String output,
        int inputTokens,
        int outputTokens,
        long latencyMs,
        String errorReason
) {

    /** 成功语义 —— {@code errorReason == null}。 */
    public boolean succeeded() {
        return errorReason == null;
    }

    /**
     * 估算成本(USD),定价与 {@link com.xilidou.jooj.http.dto.Usage#estimatedCostUsd}
     * 保持一致 —— Claude Sonnet 4.6 的价目($3 / MTok input, $15 / MTok output)。
     *
     * <p>输入不同模型时价格会有偏差,这里只做"数量级可见性",不做精确核算。
     * 精确账单交给 Week11 Task 3 的 Trace 层。
     */
    public double estimatedCostUsd() {
        return (inputTokens * 3.0 + outputTokens * 15.0) / 1_000_000.0;
    }

    /** 便捷工厂 —— mock/包装场景常见,token/延时都不关心时用。 */
    public static AgentInvocation ofText(String output) {
        return new AgentInvocation(output, 0, 0, 0L, null);
    }
}
