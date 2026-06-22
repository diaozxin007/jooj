package com.xilidou.marvis.harness.hook.impl;

import com.xilidou.marvis.harness.hook.Hook;
import com.xilidou.marvis.harness.http.dto.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MetricsHook - 统计工具调用次数 / 耗时 / 失败率。
 *
 * <h3>设计亮点：一个类实现两个 hook 接口</h3>
 *
 * <p>本类同时实现 {@link Hook.OnPreToolUse} 和 {@link Hook.OnPostToolUse}：
 * <ul>
 *   <li>Pre 时记录 {@code tool_use_id → 开始时间纳秒}</li>
 *   <li>Post 时查表算耗时，更新 {@link ToolMetric}</li>
 * </ul>
 *
 * <p>Spring 看到这个 Bean 同时实现两个接口，会**把同一个 instance 同时放进
 * {@code List<OnPreToolUse>} 和 {@code List<OnPostToolUse>}**——所以 Pre/Post
 * 跨事件的状态可以通过实例字段共享。
 *
 * <h3>关键技术点：用 nanoTime 不用 currentTimeMillis</h3>
 *
 * <p>{@code System.currentTimeMillis()} 反映"墙上时间"——可被 NTP 同步往后跳，
 * 算耗时会出现"负数"或巨大跳变。{@link System#nanoTime()} 是**单调递增**的
 * "时间差测量"接口，永远适合测延迟。
 *
 * <p>Java 时间 API 的经典坑：
 * <pre>
 *   ❌ long start = System.currentTimeMillis(); ... long elapsed = System.currentTimeMillis() - start;
 *   ✅ long start = System.nanoTime();          ... long elapsed = System.nanoTime() - start;
 * </pre>
 *
 * <h3>失败的判定</h3>
 *
 * <p>当前判定为"output 以 Error 开头"——这是 BashSkill / FileSystemSkill 的约定：
 * 失败时返回 {@code "Error: ..."}。如果 Skill 不遵循这个约定，metric 会偏低。
 *
 * <p>**更准确的做法**：让 {@link com.xilidou.marvis.harness.entity.ToolResult} 的
 * {@code success} 字段进入 PostToolUse 参数。这需要改 Hook 接口，留给将来。
 *
 * <h3>线程安全</h3>
 *
 * <p>{@link #startTimes} 用 {@link ConcurrentHashMap}，
 * {@link ToolMetric} 内部用 {@link java.util.concurrent.atomic.AtomicLong}。
 * 当前 Loop 是单线程的，但 Week 8 后台任务、Week 9 多 Agent 会有并发。
 */
@Component
@Slf4j
public class MetricsHook implements Hook.OnPreToolUse, Hook.OnPostToolUse {

    /**
     * tool_use_id → 开始时间（nanoTime）。
     * Pre 时 put，Post 时 remove + 计算。
     */
    private final Map<String, Long> startTimes = new ConcurrentHashMap<>();

    /**
     * toolName → 累计指标。
     */
    private final Map<String, ToolMetric> metrics = new ConcurrentHashMap<>();

    /**
     * Pre 事件：记录开始时间。
     */
    @Override
    public Optional<String> handle(ToolUseBlock toolUse) {
        startTimes.put(toolUse.getId(), System.nanoTime());
        // 不阻止 loop
        return Optional.empty();
    }

    /**
     * Post 事件：查表算耗时，更新指标。
     */
    @Override
    public Optional<String> handle(ToolUseBlock toolUse, String output) {
        Long start = startTimes.remove(toolUse.getId());

        if (start == null) {
            // 异常情况：Pre 没记录就来了 Post（hook 注册顺序错了？）
            log.warn("[Metrics] PostToolUse 没找到对应的 Pre 起始时间: tool_use_id={}",
                    toolUse.getId());
            return Optional.empty();
        }

        long elapsedNanos = System.nanoTime() - start;
        ToolMetric metric = metrics.computeIfAbsent(toolUse.getName(), ToolMetric::new);
        metric.incrementCall();
        metric.addLatencyNanos(elapsedNanos);

        // 简单的失败判定：output 以 "Error" 开头
        // 这是 BashSkill/FileSystemSkill 的约定（PermissionHook 也用 "Permission denied:" 前缀）
        boolean failed = output != null && (output.startsWith("Error") || output.startsWith("Permission denied"));
        if (failed) {
            metric.incrementFailure();
        }

        // INFO 级别打印每次调用——方便观察 metric 工作；生产环境可降到 DEBUG
        log.info("[Metrics] {} took {} ms{}",
                toolUse.getName(),
                elapsedNanos / 1_000_000,
                failed ? " (FAILED)" : "");

        return Optional.empty();
    }

    // ── 观察 API（测试 / Week 11 评测 / debug 用）──────────────

    /**
     * 获取某个工具的累计指标。返回 null 表示该工具从未被调用。
     */
    public ToolMetric getMetric(String toolName) {
        return metrics.get(toolName);
    }

    /**
     * 所有工具的指标快照（不可变 view）。
     */
    public Map<String, ToolMetric> snapshot() {
        return Collections.unmodifiableMap(metrics);
    }

    /**
     * 打印汇总（用于 Stop hook 或手动调用）。
     */
    public String summary() {
        if (metrics.isEmpty()) return "(no tool calls)";

        StringBuilder sb = new StringBuilder("Tool metrics:\n");
        metrics.values().stream()
                .sorted((a, b) -> Long.compare(b.getCallCount(), a.getCallCount()))
                .forEach(m -> sb.append("  ").append(m).append("\n"));
        return sb.toString();
    }

    /**
     * 测试用：清空所有指标。
     */
    public void reset() {
        startTimes.clear();
        metrics.clear();
    }
}
