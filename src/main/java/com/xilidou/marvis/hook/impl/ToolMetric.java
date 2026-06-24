package com.xilidou.marvis.hook.impl;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个工具的指标聚合。
 *
 * <p>线程安全：所有计数器和累加器用 {@link AtomicLong}，
 * 支持 Week 8 后台任务、Week 9 多 Agent 并发场景。
 *
 * <p>不持有时间窗口——这是**累计**指标。如果将来要做"过去 5 分钟"窗口，
 * 应该用 {@link com.codahale.metrics.Meter} 之类专业库（Micrometer / Dropwizard）。
 *
 * <p>只读快照通过 {@link #snapshot()} 提供。
 */
public class ToolMetric {

    @Getter
    private final String toolName;

    private final AtomicLong callCount = new AtomicLong();
    private final AtomicLong totalLatencyNanos = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();

    public ToolMetric(String toolName) {
        this.toolName = toolName;
    }

    /** 增加一次调用计数 */
    public void incrementCall() {
        callCount.incrementAndGet();
    }

    /** 累加一次延迟（纳秒）*/
    public void addLatencyNanos(long nanos) {
        totalLatencyNanos.addAndGet(nanos);
    }

    /** 增加一次失败计数 */
    public void incrementFailure() {
        failureCount.incrementAndGet();
    }

    /**
     * 平均延迟（毫秒）。无调用时返回 0。
     */
    public double avgLatencyMs() {
        long calls = callCount.get();
        if (calls == 0) return 0.0;
        return (totalLatencyNanos.get() / (double) calls) / 1_000_000.0;
    }

    /**
     * 失败率（0.0 ~ 1.0）。无调用时返回 0。
     */
    public double failureRate() {
        long calls = callCount.get();
        if (calls == 0) return 0.0;
        return failureCount.get() / (double) calls;
    }

    public long getCallCount() {
        return callCount.get();
    }

    public long getFailureCount() {
        return failureCount.get();
    }

    @Override
    public String toString() {
        return String.format("%s: calls=%d, avgMs=%.2f, fail=%.0f%%",
                toolName, getCallCount(), avgLatencyMs(), failureRate() * 100);
    }
}
