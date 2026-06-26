package com.xilidou.marvis.config;

import com.xilidou.marvis.MarvisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * marvis 统一线程模型 —— 替代 4 处 {@code new Thread().setDaemon(true).start()}。
 *
 * <h3>分两类池</h3>
 *
 * <ul>
 *   <li><b>{@link #marvisTaskScheduler() taskScheduler}</b> ——
 *       长期循环任务(cron scheduler / processor)用 {@link ThreadPoolTaskScheduler}。
 *       配合 {@link org.springframework.scheduling.annotation.Scheduled @Scheduled} 注解,
 *       业务代码只写 tick 方法,不再管 thread 启停 / sleep / interrupt</li>
 *   <li><b>{@link #marvisWorkerExecutor() workerExecutor}</b> ——
 *       短/中期一次性任务(bg 工具调用 / teammate spawn)用 cached 风格 ThreadPoolExecutor。
 *       core=0 max=N keepAlive=60s,空闲全部回收。{@link ThreadPoolExecutor.AbortPolicy}
 *       让池满时抛 {@link java.util.concurrent.RejectedExecutionException},
 *       由 caller(BackgroundTaskManager / Teammate)接住返友好错误给 LLM</li>
 * </ul>
 *
 * <h3>{@link EnableScheduling} 必需</h3>
 *
 * <p>没有这个注解,{@code @Scheduled} 不会被 Spring 处理。
 * marvis 4.x Spring Boot 默认不开 scheduling,要显式 enable。
 *
 * <h3>Spring 接管生命周期</h3>
 *
 * <ul>
 *   <li>{@link ThreadPoolTaskScheduler} 实现 {@link org.springframework.beans.factory.DisposableBean},
 *       容器 shutdown 时自动 destroy</li>
 *   <li>{@link ExecutorService} 用 {@code destroyMethod} 触发 shutdown</li>
 * </ul>
 */
@Configuration
@EnableScheduling
@Slf4j
public class MarvisExecutors {

    public static final String SCHEDULER_BEAN = "marvisTaskScheduler";
    public static final String WORKER_BEAN = "marvisWorkerExecutor";

    /**
     * 长期循环任务的调度池。被 {@code @Scheduled} 自动用作默认 task scheduler。
     *
     * <p>容量 = {@link MarvisProperties.Concurrency#schedulerPoolSize}(默认 4)。
     * 这个池要容纳所有 {@code @Scheduled(fixedDelay)} / {@code fixedRate} 任务,
     * 当前 marvis 有 2 个(cron scheduler + cron processor),给 4 槽留余量。
     */
    @Bean(name = SCHEDULER_BEAN)
    public ThreadPoolTaskScheduler marvisTaskScheduler(MarvisProperties props) {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(props.getConcurrency().getSchedulerPoolSize());
        s.setThreadNamePrefix("marvis-sched-");
        s.setDaemon(true);                                  // JVM 退出时不被 hang
        s.setWaitForTasksToCompleteOnShutdown(false);
        s.setAwaitTerminationSeconds(2);
        s.setRemoveOnCancelPolicy(true);
        s.initialize();
        log.info("[Executors] task scheduler started (poolSize={})",
                props.getConcurrency().getSchedulerPoolSize());
        return s;
    }

    /**
     * 一次性任务的工作池(bg / teammate)。
     *
     * <p>设计:
     * <ul>
     *   <li>core=0 + SynchronousQueue + max=N → "cached" 模式:
     *       有空闲 thread 用空闲的,没有就新建到 max,满 max 直接拒绝</li>
     *   <li>keepAlive=60s → 空闲 thread 60s 后回收(包括 core,因为 core=0)</li>
     *   <li>{@link ThreadPoolExecutor.AbortPolicy} → 满时抛
     *       {@link java.util.concurrent.RejectedExecutionException},
     *       caller 接住返"Error: too many concurrent tasks",LLM 看到会自我调整</li>
     * </ul>
     *
     * <p>为什么不用 {@link Executors#newCachedThreadPool}:它的 max 是
     * {@link Integer#MAX_VALUE},满世界 spawn 没上限。marvis 单 agent 同时可能 10+ 队友,
     * 给 32 槽够用且防爆炸。
     */
    @Bean(name = WORKER_BEAN, destroyMethod = "shutdown")
    public ExecutorService marvisWorkerExecutor(MarvisProperties props) {
        int max = props.getConcurrency().getWorkerMaxSize();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                0, max,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                namedDaemonThreadFactory("marvis-worker-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        // 允许 core=0 时 keepAlive 也对 core 生效(JDK 17 默认 core 不超时,
        // 但我们 core=0 没影响;保险起见显式开)
        pool.allowCoreThreadTimeOut(true);
        log.info("[Executors] worker pool started (max={})", max);
        return pool;
    }

    /** 自带 daemon + 命名前缀的 ThreadFactory。 */
    private static ThreadFactory namedDaemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, prefix + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
