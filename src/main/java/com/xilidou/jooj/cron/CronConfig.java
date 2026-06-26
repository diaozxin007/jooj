package com.xilidou.jooj.cron;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Cron Scheduler 配置 —— 跟 {@link com.xilidou.jooj.tasks.TaskConfig} 同形态。
 *
 * <p>纯 POJO,无 Spring 依赖;测试用全参构造器覆盖默认值,生产用
 * {@link CronConfiguration} 从 {@link com.xilidou.jooj.JoojProperties.Cron} 转出。
 */
public class CronConfig {

    private final Path durablePath;
    private final long schedulerTickMs;
    private final long processorTickMs;

    /** 默认值构造器(生产用):cwd/.scheduled_tasks.json + 1s scheduler tick + 200ms processor tick。 */
    public CronConfig() {
        this(defaultDurablePath(), 1000L, 200L);
    }

    /** 全参构造器(测试 / 自定义)。 */
    public CronConfig(Path durablePath, long schedulerTickMs, long processorTickMs) {
        if (durablePath == null) {
            throw new IllegalArgumentException("durablePath must not be null");
        }
        if (schedulerTickMs <= 0) {
            throw new IllegalArgumentException("schedulerTickMs must be > 0");
        }
        if (processorTickMs <= 0) {
            throw new IllegalArgumentException("processorTickMs must be > 0");
        }
        this.durablePath = durablePath;
        this.schedulerTickMs = schedulerTickMs;
        this.processorTickMs = processorTickMs;
    }

    private static Path defaultDurablePath() {
        return Paths.get(System.getProperty("user.dir"), ".scheduled_tasks.json");
    }

    /** 持久化文件路径,默认 {@code <cwd>/.scheduled_tasks.json}。 */
    public Path durablePath() {
        return durablePath;
    }

    /** Layer 1 CronScheduler thread 轮询间隔(毫秒)。 */
    public long schedulerTickMs() {
        return schedulerTickMs;
    }

    /** Layer 3 CronQueueProcessor thread 轮询间隔(毫秒)。 */
    public long processorTickMs() {
        return processorTickMs;
    }
}
