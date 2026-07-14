package com.xilidou.jooj.compact;

import com.xilidou.jooj.http.AnthropicProperties;
import com.xilidou.jooj.llm.LlmClient;
import com.xilidou.jooj.memory.MemoryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Compact 子系统的 Spring 装配 —— R3 重构(2026-06-24)从
 * {@code harness.JoojBeansConfig} 拆出。
 *
 * <p>提供 2 个 Bean:
 * <ul>
 *   <li>{@link CompactConfig} —— 从 {@link CompactProperties} 拍平成 POJO 配置</li>
 *   <li>{@link CompactPipeline} —— 把 config + client + model 拼起来,启用 L1-L4 四层压缩</li>
 * </ul>
 *
 * <p><b>为什么 {@link CompactPipeline} / {@link CompactConfig} 不自己 @Component</b>:
 * 它们是无 Spring 依赖的纯 POJO(参考 EVOLUTION 文档 s08 的设计要点),
 * 加 {@code @Component} 会把 Spring 注解污染到 model 层,且会让"传 null model"
 * 的旧测试构造器与 Spring 自动构造器签名重叠产生歧义。
 *
 * <p>用 {@code @Bean} 装配让 Spring "粘合"得起,但被装的对象本身保持
 * framework-agnostic —— 测试里直接 {@code new CompactPipeline(...)} 完全不依赖容器。
 */
@Configuration
public class CompactConfiguration {

    /**
     * 把 {@link CompactProperties} 拍平成 {@link CompactConfig}。
     *
     * <p>{@code taskOutputDir} / {@code transcriptDir} 用 {@code CompactConfig} 的
     * 静态常量(默认指向 cwd 下的 {@code .task_outputs} / {@code .transcripts}),
     * 暂未暴露到 yaml(配置面已经够多)——需要时再加。
     */
    @Bean
    public CompactConfig compactConfig(CompactProperties c) {
        return new CompactConfig(
                c.getMaxMessages(),
                c.getSnipHeadKeep(),
                c.getKeepRecent(),
                c.getMinPlaceholderLen(),
                c.getMaxToolResultBytes(),
                CompactConfig.defaultTaskOutputDir(),
                c.getSummaryHeadKeep(),
                c.getSummaryTailKeep(),
                CompactConfig.defaultTranscriptDir(),
                c.getSummaryMaxChars()
        );
    }

    /**
     * {@link CompactPipeline} Bean:把 {@link CompactConfig} / {@link LlmClient} /
     * model / {@link MemoryService} 拼起来。
     *
     * <ul>
     *   <li>L4 reactive 摘要走 canonical vendor-neutral client</li>
     *   <li>s21 Demo 24:把 MemoryService 注入 pipeline,启用 pre-compression extraction
     *       —— L4 触发前先抢救永久 fact 进 MEMORY.md,再让 L4 摘要(防"被压缩 lossy 丢失")</li>
     *   <li>s22 D:token-aware 触发 —— 从 {@link CompactProperties} 读 contextLength +
     *       thresholdPercent,pipeline 内部据此判定 shouldCompress</li>
     * </ul>
     */
    @Bean
    public CompactPipeline compactPipeline(CompactConfig config,
                                           LlmClient client,
                                           CompactProperties c,
                                           AnthropicProperties anthropic,
                                           MemoryService memoryService) {
        return new CompactPipeline(config, client, anthropic.getModel(), memoryService,
                c.getContextLength(), c.getThresholdPercent());
    }
}
