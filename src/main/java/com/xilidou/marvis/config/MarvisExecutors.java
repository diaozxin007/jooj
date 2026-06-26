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
 * <h3>分三类池(Stage 3 拆分)</h3>
 *
 * <ul>
 *   <li><b>{@link #marvisTaskScheduler() taskScheduler}</b> ——
 *       长期循环任务(cron scheduler / processor)用 {@link ThreadPoolTaskScheduler}。
 *       配合 {@link org.springframework.scheduling.annotation.Scheduled @Scheduled} 注解,
 *       业务代码只写 tick 方法,不再管 thread 启停 / sleep / interrupt</li>
 *   <li><b>{@link #marvisBgExecutor() bgExecutor}</b> ——
 *       BG 慢工具调用(s13)。**{@link ThreadPoolExecutor.CallerRunsPolicy}**:
 *       池满时调用线程自己跑 → 退化为同步工具调用,LLM 仍能拿到结果只是慢一点。
 *       适合 bg 路径:**满了降级,不告诉 LLM**</li>
 *   <li><b>{@link #marvisTeammateExecutor() teammateExecutor}</b> ——
 *       Teammate spawn(s15+)。**{@link ThreadPoolExecutor.AbortPolicy}**:
 *       池满时抛 {@link java.util.concurrent.RejectedExecutionException},
 *       caller(Teammate.spawn)接住返"Error: pool full"给 LLM。
 *       适合 teammate 路径:**满了不能 inline 跑**(会卡死 agent loop 几分钟),
 *       告诉 LLM 让它降并发</li>
 * </ul>
 *
 * <h3>为什么 bg 跟 teammate 用不同策略</h3>
 *
 * <table>
 *   <tr><th></th><th>BG (CallerRuns)</th><th>Teammate (Abort)</th></tr>
 *   <tr><td>池满时 caller 阻塞多久</td><td>1 个工具调用(秒~分)</td><td>整个 agent loop(分~几十分)</td></tr>
 *   <tr><td>阻塞期间副作用</td><td>本轮工具同步跑完</td><td>用户输入 / cron / 其他 teammate 全卡</td></tr>
 *   <tr><td>策略选择</td><td>降级同步可接受</td><td>必须立即返回 + 告诉 LLM</td></tr>
 * </table>
 *
 * <h3>{@link EnableScheduling} 必需</h3>
 *
 * <p>没有这个注解,{@code @Scheduled} 不会被 Spring 处理。
 * marvis 4.x Spring Boot 默认不开 scheduling,要显式 enable。
 *
 * <h3>Spring 接管生命周期</h3>
 *
 * <ul>
 *   <li>{@link ThreadPoolTaskScheduler} 实现 {@code DisposableBean},容器 shutdown 时自动 destroy</li>
 *   <li>{@link ExecutorService} 用 {@code destroyMethod = "shutdown"} 触发优雅关闭</li>
 * </ul>
 */
@Configuration
@EnableScheduling
@Slf4j
public class MarvisExecutors {

    public static final String SCHEDULER_BEAN = "marvisTaskScheduler";
    public static final String BG_BEAN = "marvisBgExecutor";
    public static final String TEAMMATE_BEAN = "marvisTeammateExecutor";

    /**
     * 长期循环任务的调度池。被 {@code @Scheduled} 自动用作默认 task scheduler。
     *
     * <p>容量 = {@link MarvisProperties.Concurrency#schedulerPoolSize}(默认 4)。
     * 当前 marvis 有 2 个 @Scheduled 任务(cron scheduler + cron processor),
     * 4 槽留余量。
     */
    @Bean(name = SCHEDULER_BEAN)
    public ThreadPoolTaskScheduler marvisTaskScheduler(MarvisProperties props) {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(props.getConcurrency().getSchedulerPoolSize());
        s.setThreadNamePrefix("marvis-sched-");
        s.setDaemon(true);
        s.setWaitForTasksToCompleteOnShutdown(false);
        s.setAwaitTerminationSeconds(2);
        s.setRemoveOnCancelPolicy(true);
        s.initialize();
        log.info("[Executors] task scheduler started (poolSize={})",
                props.getConcurrency().getSchedulerPoolSize());
        return s;
    }

    /**
     * BG 慢工具调用专用池。**{@link ThreadPoolExecutor.CallerRunsPolicy}** 池满降级同步。
     *
     * <p>典型场景:LLM 同时派 N 个 {@code bash + run_in_background=true},
     * 池里 8 槽全占用时第 9 个会在 caller(agent_loop)线程 inline 跑 ——
     * 等于本次没派 bg,跟同步工具调用等价。LLM 仍能拿到结果,只是这一轮慢一点。
     *
     * <p>容量 = {@link MarvisProperties.Concurrency#bgPoolSize}(默认 8)。
     */
    @Bean(name = BG_BEAN, destroyMethod = "shutdown")
    public ExecutorService marvisBgExecutor(MarvisProperties props) {
        int max = props.getConcurrency().getBgPoolSize();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                0, max,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                namedDaemonThreadFactory("marvis-bg-"),
                new ThreadPoolExecutor.CallerRunsPolicy()        // ← 满则同步降级
        );
        pool.allowCoreThreadTimeOut(true);
        log.info("[Executors] bg pool started (max={}, policy=CallerRuns)", max);
        return pool;
    }

    /**
     * Teammate spawn 专用池。**{@link ThreadPoolExecutor.AbortPolicy}** 池满抛异常。
     *
     * <p>不能用 CallerRunsPolicy:teammate.runLoop 包含 active turns(≤30 LLM call)
     * + idle loop(≤5 分钟),inline 跑会卡死整个 agent loop 几分钟到几十分钟,
     * 期间用户输入 / cron / 其他 teammate 全卡。改用 AbortPolicy,池满时抛
     * {@link java.util.concurrent.RejectedExecutionException},
     * Teammate.spawn 接住返"Error: pool full"给 LLM,LLM 自己降并发。
     *
     * <p>容量 = {@link MarvisProperties.Concurrency#teammatePoolSize}(默认 16)。
     * 典型 multi-agent 场景同时活跃 teammate ≤10,16 槽留余量。
     */
    @Bean(name = TEAMMATE_BEAN, destroyMethod = "shutdown")
    public ExecutorService marvisTeammateExecutor(MarvisProperties props) {
        int max = props.getConcurrency().getTeammatePoolSize();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                0, max,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                namedDaemonThreadFactory("marvis-teammate-"),
                new ThreadPoolExecutor.AbortPolicy()             // ← 满则抛异常给 caller
        );
        pool.allowCoreThreadTimeOut(true);
        log.info("[Executors] teammate pool started (max={}, policy=Abort)", max);
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
