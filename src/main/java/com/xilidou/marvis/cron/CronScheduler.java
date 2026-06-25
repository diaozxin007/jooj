package com.xilidou.marvis.cron;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Cron 4 层架构 — Layer 1:scheduler daemon thread。
 *
 * <p>每 {@link CronConfig#schedulerTickMs} 毫秒(默认 1000ms)调一次
 * {@link CronService#fireMatching},把匹配 {@code now} 的 job 入队。
 *
 * <p>对应上游 s14 {@code _scheduler_loop} 的 thread 形态。
 *
 * <h3>生命周期</h3>
 *
 * <p>{@link PostConstruct} 启动 thread,{@link PreDestroy} 标记 running=false +
 * interrupt thread。Spring 容器接管,不需要手动 start/stop。
 *
 * <h3>容错</h3>
 *
 * <p>单 tick 抛异常不杀 thread —— catch 记 warn 后继续下一个 tick。
 * Thread 是 daemon,JVM 退出时不会被 hang 住。
 */
@Component
@Slf4j
public class CronScheduler {

    private final CronService service;
    private final long tickMs;

    private volatile boolean running = false;
    private Thread thread;

    public CronScheduler(CronService service, CronConfig config) {
        this.service = service;
        this.tickMs = config.schedulerTickMs();
    }

    @PostConstruct
    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "marvis-cron-scheduler");
        thread.setDaemon(true);
        thread.start();
        log.info("[Cron] scheduler thread started (tick={}ms)", tickMs);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** 主循环 —— sleep N ms → fire matching → 重复。 */
    private void loop() {
        while (running) {
            try {
                Thread.sleep(tickMs);
                service.fireMatching(LocalDateTime.now());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 单 tick 失败不杀 thread —— scheduler 必须长寿命运行
                log.warn("[Cron] scheduler tick failed: {}", e.toString());
            }
        }
    }

    /** 测试用:thread 是否活着。 */
    public boolean isRunning() {
        return running && thread != null && thread.isAlive();
    }
}
