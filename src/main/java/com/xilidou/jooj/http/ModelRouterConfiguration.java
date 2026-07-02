package com.xilidou.jooj.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 将所有 {@link ModelProvider} bean 组装为 {@link ModelRouter},
 * 暴露为唯一的 {@link AnthropicClient} bean。
 *
 * <p>这样所有 caller(AgentLoopHarness / RecoveryCoordinator / MemoryExtractor / ...)
 * 注入 {@link AnthropicClient} 时拿到的是路由器,无需任何代码改动。
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
    public AnthropicClient modelRouter(List<ModelProvider> providers) {
        return new ModelRouter(providers);
    }
}
