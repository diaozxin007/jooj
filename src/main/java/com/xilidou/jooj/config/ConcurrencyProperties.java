package com.xilidou.jooj.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 三类线程池上限的 yml → Java 桥接。
 *
 * <p>字段简单(3 个 int),豁免三分法(参考 Mcp / Permission),直接由
 * {@link JoojExecutors} 消费。
 *
 * <p><b>归属决策</b>(D-07):留在 {@code config/} 包与 {@link JoojExecutors} 同居,
 * 不建独立 {@code concurrency/}(参见 [[Jooj项目_配置架构重构_规划]]):
 * <ul>
 *   <li>{@code config/} 角色 = "跨子系统的全局基础设施"(线程池 + JSON 工厂)</li>
 *   <li>建独立 {@code concurrency/} 会让 {@link JsonMappers} 孤零零留在 {@code config/}</li>
 * </ul>
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.Concurrency} 拆出,前缀 {@code jooj.concurrency}
 * 保持不变。
 */
@Data
@ConfigurationProperties("jooj.concurrency")
public class ConcurrencyProperties {

    /** 长期循环任务({@code @Scheduled})的调度池容量。默认 4。 */
    private int schedulerPoolSize = 4;

    /** BG 慢工具池上限(s13)。CallerRunsPolicy 满则同步降级。默认 8。 */
    private int bgPoolSize = 8;

    /** Teammate spawn 池上限(s15+)。AbortPolicy 满则返 Error 给 LLM。默认 16。 */
    private int teammatePoolSize = 16;
}
