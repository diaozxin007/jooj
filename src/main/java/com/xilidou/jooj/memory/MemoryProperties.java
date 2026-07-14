package com.xilidou.jooj.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Memory 子系统(s09 / Hermes Tier 3)的 yml → Java 桥接。
 *
 * <p>三分法(参见 [[Jooj项目_配置架构重构_规划]] D-05):
 * <ul>
 *   <li>{@link MemoryProperties}(本类)—— {@code @ConfigurationProperties("jooj.memory")}</li>
 *   <li>{@link MemoryConfig} —— 运行时 POJO,{@code memoryDir} 已解析为绝对 {@link java.nio.file.Path}</li>
 *   <li>{@link MemoryConfiguration} —— {@code @Bean} 装配</li>
 * </ul>
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.Memory} 拆出,前缀 {@code jooj.memory} 保持不变。
 */
@Data
@ConfigurationProperties("jooj.memory")
public class MemoryProperties {

    /** memory 文件目录(相对 cwd 或绝对路径)。 */
    private String memoryDir = ".memory";

    /** 索引文件名,放在 {@link #memoryDir} 下。 */
    private String indexFilename = "MEMORY.md";

    /** 单条 memory body 最大字符数(超出截断)。 */
    private int maxBodyBytes = 4096;

    /** memory 文件数 ≥ 此阈值时触发 consolidate。 */
    private int consolidateThreshold = 10;

    /**
     * s21 Demo 27 / Hermes Tier 3 P3.2:write_approval staged 写。
     * <ul>
     *   <li>{@code false}(默认)— Reviewer 提案直接 store.write 生效(Demo 26 行为)</li>
     *   <li>{@code true} — Reviewer 提案进 pending pool,等用户 {@code /memory approve}</li>
     * </ul>
     *
     * <p>实战建议:Reviewer 跑稳之前先开 true 看提案质量,稳定后再切 false。
     */
    private boolean writeApproval = false;
}
