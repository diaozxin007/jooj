package com.xilidou.marvis.cron;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Cron 4 层架构 — Layer 1:scheduler 定时调度。
 *
 * <p>每 {@link CronConfig#schedulerTickMs} 毫秒(默认 1000ms)调一次
 * {@link CronService#fireMatching},把匹配 {@code now} 的 job 入队。
 *
 * <p>对应上游 s14 {@code _scheduler_loop} 的 thread 形态。
 *
 * <h3>线程模型</h3>
 *
 * <p>线程重构后用 Spring {@code @Scheduled} 替代裸 {@code new Thread().start()}:
 * <ul>
 *   <li>tick 方法跑在 {@link com.xilidou.marvis.config.MarvisExecutors#marvisTaskScheduler 共享调度池}</li>
 *   <li>Spring 容器接管启停,JVM 退出时 daemon thread 自动结束</li>
 *   <li>{@code fixedDelayString} 用 yaml 占位符 + SpEL 表达式注入 tick 间隔</li>
 * </ul>
 *
 * <h3>容错</h3>
 *
 * <p>单 tick 抛异常不会停止后续 tick —— Spring 默认在错误处理器里 log 后继续。
 * 我们再加一层 try-catch 把 warn 收紧,跟原 daemon loop 行为一致。
 */
@Component
@Slf4j
public class CronScheduler {

    private final CronService service;

    public CronScheduler(CronService service) {
        this.service = service;
    }

    /**
     * Layer 1 主 tick:每 schedulerTickMs 毫秒扫一次所有 cron job,把匹配 {@code now}
     * 的入队。
     *
     * <p>{@code fixedDelayString} 用 yaml 占位符直接读 {@code marvis.cron.scheduler-tick-ms},
     * 默认 1000。{@code initialDelayString} 给容器一点预热时间(500ms)。
     *
     * <p>{@code fixedDelay} 而非 {@code fixedRate}:fixedDelay 是"上一次结束后再等 N",
     * 防止 tick 跑慢时挤压;fixedRate 是"严格每 N 必跑",可能堆积。我们要前者。
     */
    @Scheduled(
            fixedDelayString = "${marvis.cron.scheduler-tick-ms:1000}",
            initialDelayString = "${marvis.cron.scheduler-initial-delay-ms:500}"
    )
    public void tick() {
        try {
            service.fireMatching(LocalDateTime.now());
        } catch (Exception e) {
            // 单 tick 失败不影响下次 —— Spring scheduler 会继续调度
            log.warn("[Cron] scheduler tick failed: {}", e.toString());
        }
    }
}
