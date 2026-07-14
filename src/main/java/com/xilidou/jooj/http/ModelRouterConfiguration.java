package com.xilidou.jooj.http;

import com.xilidou.jooj.llm.LlmClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 将所有 {@link ModelProvider} bean 组装为 {@link ModelRouter},
 * 暴露为唯一的 {@link AnthropicClient} bean(以及 P2 canonical {@link LlmClient} bean)。
 *
 * <p>这样所有 caller(AgentLoopHarness / RecoveryCoordinator / MemoryExtractor / ...)
 * 注入 {@link AnthropicClient} 或(P2 之后){@link LlmClient} 时拿到的都是同一个路由器,
 * 无需任何代码改动。
 *
 * <p>{@code @ConditionalOnMissingBean} 确保当测试环境中已有
 * {@code MockAnthropicClient}(@Primary) 时,本 bean 不会创建,避免冲突。
 */
@Configuration
public class ModelRouterConfiguration {

    /**
     * 收集容器中所有 {@link ModelProvider} bean,构建路由器。
     * 第一个 provider(通常是 Anthropic)作为默认 fallback。
     *
     * <p>使用 {@code @ConditionalOnMissingBean} —— 生产环境中这是唯一的
     * {@link AnthropicClient} bean;测试环境中 {@code JoojTestConfig} 已定义
     * {@code MockAnthropicClient},此 bean 自动退让。
     */
    @Bean
    @ConditionalOnMissingBean(AnthropicClient.class)
    public ModelRouter modelRouter(List<ModelProvider> providers) {
        return new ModelRouter(providers);
    }

    /**
     * Expose the router as the canonical {@link LlmClient}. In production this
     * is the same instance as the {@link AnthropicClient} bean; in test contexts
     * where a mock replaces the router, this bean is absent and any P2-canonical
     * caller must provide its own mock (via {@code @MockBean LlmClient}).
     *
     * <p>Uses {@link ObjectProvider} so the bean gracefully absents itself when
     * no {@link ModelRouter} was created — the {@code @ConditionalOnMissingBean}
     * gate on {@link #modelRouter} would otherwise cascade an unsatisfied-dependency
     * exception into this bean under test.
     */
    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient llmClient(ObjectProvider<ModelRouter> router) {
        ModelRouter r = router.getIfAvailable();
        if (r == null) {
            // No router in this context (test with mock AnthropicClient). Return a
            // no-op that fails loudly if used; canonical callers in test setups must
            // supply their own mock LlmClient explicitly.
            return req -> {
                throw new IllegalStateException(
                        "No LlmClient available — this Spring context uses a mock "
                                + "AnthropicClient but did not register a mock LlmClient. "
                                + "Register one with @MockBean or an @Bean override.");
            };
        }
        return r;
    }
}
