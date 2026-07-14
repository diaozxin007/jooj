package com.xilidou.jooj.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cron Scheduler(s14)的 Spring 装配。
 *
 * <p>跟 {@link com.xilidou.jooj.tasks.TasksConfiguration} 同模式 ——
 * model 层(CronConfig / CronStore / CronService)不自己 {@code @Component},
 * 测试可以 {@code new ...} 不依赖容器。
 *
 * <p>thread 层({@link CronScheduler} + {@link CronQueueProcessor})走 {@code @Component}
 * 自动扫描 + Spring 生命周期接管(@PostConstruct / @PreDestroy)。
 *
 * <h3>{@code agentLock} Bean</h3>
 *
 * <p>本类同时暴露 {@code agentLock} 单例 ——
 * {@link com.xilidou.jooj.agent.AgentLoopHarness} 和 {@link CronQueueProcessor}
 * 共享同一把锁,防 cron-fired turn 跟 user-input turn 撞车。
 */
@Configuration
public class CronConfiguration {

    @Bean
    public CronConfig cronConfig(CronProperties props) {
        Path durablePath = Paths.get(props.getDurablePath())
                .toAbsolutePath().normalize();
        return new CronConfig(
                durablePath,
                props.getSchedulerTickMs(),
                props.getProcessorTickMs()
        );
    }

    @Bean
    public CronStore cronStore(CronConfig config,
                               @Qualifier("joojObjectMapper") ObjectMapper json) {
        return new CronStore(config, json);
    }

    @Bean
    public CronService cronService(CronStore store) {
        return new CronService(store);
    }

    /**
     * agentLock 共享单例 —— REPL user-input 流程跟 CronQueueProcessor 都拿它,
     * 用 {@link ReentrantLock#tryLock} 抢,失败时让出当前 tick。
     */
    @Bean(name = "agentLock")
    public ReentrantLock agentLock() {
        return new ReentrantLock();
    }
}
