package com.xilidou.jooj.memory;

import com.xilidou.jooj.JoojProperties;
import com.xilidou.jooj.config.JoojExecutors;
import com.xilidou.jooj.http.AnthropicClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;

/**
 * Memory 子系统的 Spring 装配 —— R3 重构(2026-06-24)从
 * {@code harness.JoojBeansConfig} 拆出。
 *
 * <p>提供 3 个 Bean(Demo 26 起):
 * <ul>
 *   <li>{@link MemoryConfig} —— 从 {@link JoojProperties.Memory} 转出</li>
 *   <li>{@link BackgroundReviewer} —— Hermes Tier 3 P3.1 self-improvement reviewer
 *       (本身只是 LLM caller,异步执行由 MemoryService 用 BgExecutor 接)</li>
 *   <li>{@link MemoryService} —— 启用 4+1 层 memory
 *       (Storage / Selection / Extraction / Consolidation + Reviewer)</li>
 * </ul>
 *
 * <p><b>为什么 {@link MemoryService} / {@link MemoryConfig} 不自己 @Component</b>:
 * 同 {@link com.xilidou.jooj.compact.CompactConfiguration} 的理由 ——
 * 保持 model 层 framework-agnostic,测试 {@code new MemoryService(...)} 不依赖容器。
 */
@Configuration
public class MemoryConfiguration {

    /**
     * 把 {@link JoojProperties.Memory} 转成 {@link MemoryConfig}。
     *
     * <p>{@code memoryDir} 走 {@link Path#of(String, String...)}:
     * 相对路径会以启动时的 cwd 为锚点,绝对路径直接生效。
     */
    @Bean
    public MemoryConfig memoryConfig(JoojProperties props) {
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
     * {@link BackgroundReviewer} Bean —— Hermes Tier 3 P3.1 self-improvement(s21 Demo 26)。
     *
     * <p>职责跟 {@link MemoryExtractor} 互补:Extractor 抽 fact,Reviewer 找跨 turn 模式 / 教训。
     * 本身是同步 LLM caller,异步触发由 {@link MemoryService#onTurnEnd} 用 BgExecutor 接。
     *
     * <p>注意:Reviewer 自己也用同一个 {@link MemoryStore} 写 —— 跟 Extractor 共享 store,
     * existing memory catalog 也共享(Reviewer prompt 里会列出已有 memory 让 LLM 不重复)。
     */
    @Bean
    public BackgroundReviewer backgroundReviewer(MemoryConfig config,
                                                 AnthropicClient client,
                                                 JoojProperties props) {
        return new BackgroundReviewer(
                new MemoryStore(config),
                client,
                props.getAnthropic().getModel()
        );
    }

    /**
     * {@link MemoryService} Bean:把 {@link MemoryConfig} / {@link AnthropicClient} /
     * model + {@link BackgroundReviewer} + {@code joojBgExecutor} 拼起来,启用 4+1 层 memory。
     *
     * <p>5 参 ctor 接 reviewer + executor;turn 结束时 extract+consolidate 同步跑(主路径)、
     * review 在 BgExecutor 里异步跑(turn 不阻塞)。
     */
    @Bean
    public MemoryService memoryService(MemoryConfig config,
                                       AnthropicClient client,
                                       JoojProperties props,
                                       BackgroundReviewer reviewer,
                                       @Qualifier(JoojExecutors.BG_BEAN) ExecutorService bgExecutor) {
        return new MemoryService(
                config, client, props.getAnthropic().getModel(),
                reviewer, bgExecutor
        );
    }
}
