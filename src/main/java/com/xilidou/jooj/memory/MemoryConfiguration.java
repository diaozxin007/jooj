package com.xilidou.jooj.memory;

import com.xilidou.jooj.config.JoojExecutors;
import com.xilidou.jooj.http.AnthropicClient;
import com.xilidou.jooj.http.AnthropicProperties;
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
 *   <li>{@link MemoryConfig} —— 从 {@link MemoryProperties} 转出</li>
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
     * 把 {@link MemoryProperties} 转成 {@link MemoryConfig}。
     *
     * <p>{@code memoryDir} 走 {@link Path#of(String, String...)}:
     * 相对路径会以启动时的 cwd 为锚点,绝对路径直接生效。
     */
    @Bean
    public MemoryConfig memoryConfig(MemoryProperties m) {
        Path memoryDir = Paths.get(m.getMemoryDir()).toAbsolutePath().normalize();
        return new MemoryConfig(
                memoryDir,
                m.getIndexFilename(),
                m.getMaxBodyBytes(),
                m.getConsolidateThreshold()
        );
    }

    /**
     * Memory storage 层 —— Demo 27 起作 Bean 暴露,因为 {@link com.xilidou.jooj.slashcmd.impl.MemoryCommand}
     * 在 approve 时要直接调 {@link MemoryStore#write}。
     *
     * <p>{@link BackgroundReviewer} / {@link MemoryService} 仍可以 {@code new MemoryStore(config)},
     * 不强制走 bean —— store 无内部状态,多实例对同一 dir 行为一致。
     */
    @Bean
    public MemoryStore memoryStore(MemoryConfig config) {
        return new MemoryStore(config);
    }

    /**
     * Pending memory store —— Hermes Tier 3 P3.2 staged-write pool(s21 Demo 27)。
     *
     * <p>{@link BackgroundReviewer} 在 {@code writeApproval=true} 时把提案写到这里,
     * 等用户 {@code /memory approve <id>} 才 promote 到正式 store。
     *
     * <p>共享 {@code memoryDir} 跟正式 store —— pending pool 是 {@code .memory/.pending.json}
     * 单文件,跟 memory 文件平级但 dotfile 隐藏(防被 list 当 memory 加载)。
     */
    @Bean
    public PendingMemoryStore pendingMemoryStore(MemoryConfig config) {
        return new PendingMemoryStore(config.memoryDir());
    }

    /**
     * {@link BackgroundReviewer} Bean —— Hermes Tier 3 P3.1 self-improvement(s21 Demo 26)
     * + P3.2 staged write_approval(s21 Demo 27)。
     *
     * <p>职责跟 {@link MemoryExtractor} 互补:Extractor 抽 fact,Reviewer 找跨 turn 模式 / 教训。
     * 本身是同步 LLM caller,异步触发由 {@link MemoryService#onTurnEnd} 用 BgExecutor 接。
     *
     * <p>注意:Reviewer 自己也用同一个 {@link MemoryStore} 写 —— 跟 Extractor 共享 store,
     * existing memory catalog 也共享(Reviewer prompt 里会列出已有 memory 让 LLM 不重复)。
     *
     * <p>{@code writeApproval=true} 时不直接 store.write,而是走 {@link PendingMemoryStore}
     * staged 等用户 approve。默认 false 等价 Demo 26 行为。
     */
    @Bean
    public BackgroundReviewer backgroundReviewer(MemoryStore store,
                                                 AnthropicClient client,
                                                 AnthropicProperties anthropic,
                                                 MemoryProperties memProps,
                                                 PendingMemoryStore pendingStore) {
        return new BackgroundReviewer(
                store,
                client,
                anthropic.getModel(),
                pendingStore,
                memProps.isWriteApproval()
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
                                       AnthropicProperties anthropic,
                                       BackgroundReviewer reviewer,
                                       @Qualifier(JoojExecutors.BG_BEAN) ExecutorService bgExecutor) {
        return new MemoryService(
                config, client, anthropic.getModel(),
                reviewer, bgExecutor
        );
    }
}
