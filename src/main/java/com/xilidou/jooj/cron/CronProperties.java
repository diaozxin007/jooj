package com.xilidou.jooj.cron;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cron Scheduler(s14)的 yml → Java 桥接。
 *
 * <p>三分法(参见 [[Jooj项目_配置架构重构_规划]] D-05):
 * <ul>
 *   <li>{@link CronProperties}(本类)—— {@code @ConfigurationProperties("jooj.cron")}</li>
 *   <li>{@link CronConfig} —— 运行时 POJO,{@code durablePath} 已解析为绝对 {@link java.nio.file.Path}</li>
 *   <li>{@link CronConfiguration} —— {@code @Bean} 装配</li>
 * </ul>
 *
 * <p>tick 间隔影响响应延迟与 CPU 开销。生产场景默认值即可:
 * <ul>
 *   <li>Layer 1 scheduler tick = 1000ms — 1s 检查一次哪些 job 该 fire</li>
 *   <li>Layer 3 processor tick = 200ms — 200ms 检查一次 queue 是否有 fired job</li>
 * </ul>
 *
 * <p>测试 profile 把这俩调小让 cron-fire 测试快速完成。
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.Cron} 拆出,前缀 {@code jooj.cron} 保持不变。
 */
@Data
@ConfigurationProperties("jooj.cron")
public class CronProperties {

    /** Layer 1 CronScheduler 轮询间隔(毫秒)。默认 1000。 */
    private int schedulerTickMs = 1000;

    /** Layer 3 CronQueueProcessor 轮询间隔(毫秒)。默认 200。 */
    private int processorTickMs = 200;

    /** durable 持久化文件路径(相对 cwd 或绝对)。默认 {@code .scheduled_tasks.json}。 */
    private String durablePath = ".scheduled_tasks.json";
}
