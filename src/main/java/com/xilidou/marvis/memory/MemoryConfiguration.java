package com.xilidou.marvis.memory;

import com.xilidou.marvis.MarvisProperties;
import com.xilidou.marvis.http.AnthropicClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Memory 子系统的 Spring 装配 —— R3 重构(2026-06-24)从
 * {@code harness.MarvisBeansConfig} 拆出。
 *
 * <p>提供 2 个 Bean:
 * <ul>
 *   <li>{@link MemoryConfig} —— 从 {@link MarvisProperties.Memory} 转出</li>
 *   <li>{@link MemoryService} —— 启用 4 层 memory
 *       (Storage / Selection / Extraction / Consolidation)</li>
 * </ul>
 *
 * <p><b>为什么 {@link MemoryService} / {@link MemoryConfig} 不自己 @Component</b>:
 * 同 {@link com.xilidou.marvis.compact.CompactConfiguration} 的理由 ——
 * 保持 model 层 framework-agnostic,测试 {@code new MemoryService(...)} 不依赖容器。
 */
@Configuration
public class MemoryConfiguration {

    /**
     * 把 {@link MarvisProperties.Memory} 转成 {@link MemoryConfig}。
     *
     * <p>{@code memoryDir} 走 {@link Path#of(String, String...)}:
     * 相对路径会以启动时的 cwd 为锚点,绝对路径直接生效。
     */
    @Bean
    public MemoryConfig memoryConfig(MarvisProperties props) {
        var m = props.getMemory();
        Path memoryDir = Paths.get(m.getMemoryDir()).toAbsolutePath().normalize();
        return new MemoryConfig(
                memoryDir,
                m.getIndexFilename(),
                m.getMaxBodyBytes(),
                m.getConsolidateThreshold()
        );
    }

    /**
     * {@link MemoryService} Bean:把 {@link MemoryConfig} / {@link AnthropicClient} /
     * model 拼起来,启用 4 层 memory(Storage / Selection / Extraction / Consolidation)。
     */
    @Bean
    public MemoryService memoryService(MemoryConfig config,
                                       AnthropicClient client,
                                       MarvisProperties props) {
        return new MemoryService(config, client, props.getAnthropic().getModel());
    }
}
