package com.xilidou.jooj.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JsonMappers;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * HTTP 层 Bean 装配 —— 切片 C-step2。
 *
 * <p>原 {@link AnthropicHttpClient#fromEnv} / {@link HttpAuth} 二选一逻辑搬到这里,
 * 通过 {@link Bean} 把 4 个对象交给 Spring 管理:
 * <ol>
 *   <li>{@link OkHttpClient} — 全局单例,内部连接池要复用</li>
 *   <li>{@link ObjectMapper} — Jackson 配置统一从 {@link JsonMappers#newMapper()} 来</li>
 *   <li>{@link HttpAuth} — 根据 {@code jooj.anthropic.api-key/auth-token} 二选一</li>
 *   <li>{@link AnthropicClient} — 把上面 3 个 + baseUrl 拼成最终 client</li>
 * </ol>
 *
 * <p><b>为什么 AnthropicHttpClient 不直接 @Component</b>:
 * 它有 4 个构造器参数,其中 baseUrl 是字符串 + auth 是策略接口,
 * 用 @Bean 显式装配比 @Autowired @Value 链更清晰(类比 Spring 自己的 RestTemplate)。
 *
 * <p><b>OkHttpClient 单例的重要性</b>:
 * OkHttp 内部维护连接池(默认 maxIdleConnections=5)。每次 new 一份会浪费 socket;
 * Spring 单例 Bean 天然解决这个问题。
 */
@Configuration
public class HttpClientConfiguration {

    /**
     * 二选一构造 {@link HttpAuth}:
     * <ul>
     *   <li>{@code jooj.anthropic.api-key} 非空 → {@link ApiKeyAuth}</li>
     *   <li>否则 {@code jooj.anthropic.auth-token} 非空 → {@link BearerTokenAuth}</li>
     *   <li>都为空 → 启动失败,提示用户至少配一个</li>
     * </ul>
     */
    @Bean
    public HttpAuth httpAuth(AnthropicProperties anthropic) {
        if (StringUtils.hasText(anthropic.getApiKey())) {
            return new ApiKeyAuth(anthropic.getApiKey());
        }
        if (StringUtils.hasText(anthropic.getAuthToken())) {
            return new BearerTokenAuth(anthropic.getAuthToken());
        }
        throw new IllegalStateException(
                "jooj.anthropic.api-key or jooj.anthropic.auth-token must be set " +
                        "(check application.yml or env vars ANTHROPIC_API_KEY/ANTHROPIC_AUTH_TOKEN)");
    }

    /**
     * OkHttp 单例。
     *
     * <p>超时同 {@link AnthropicHttpClient#defaultOkHttpClient()} 保持一致:
     * 10s 连接 / 120s 读 / 30s 写。改动需同步两端。
     */
    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Jackson 单例。配置统一从 {@link JsonMappers#newMapper()} 来,
     * 保留它作静态工厂方便测试和非 Spring 场景。
     *
     * <p>Bean 名字明确为 {@code joojObjectMapper},避免和 Spring Boot 4
     * 自动注册的 ObjectMapper 主 Bean 冲突(虽然实际配置一致,但显式声明更安全)。
     */
    @Bean(name = "joojObjectMapper")
    @org.springframework.context.annotation.Primary
    public ObjectMapper joojObjectMapper() {
        return JsonMappers.newMapper();
    }

    /**
     * Anthropic provider —— 直连 Anthropic API。
     *
     * <p>不再标 {@code @Primary};{@link ModelRouterConfiguration} 将其收集并通过
     * {@link ModelRouter} 以 {@code @Primary AnthropicClient} 暴露。
     *
     * <p>注意参数 {@code ObjectMapper} 用 {@code joojObjectMapper} 限定,
     * 避免与 Spring Boot 自带 jackson auto-config 的 mapper 冲突。
     */
    @Bean
    public ModelProvider anthropicProvider(OkHttpClient http,
                                          ObjectMapper joojObjectMapper,
                                          AnthropicProperties anthropic,
                                          HttpAuth auth) {
        return new AnthropicHttpClient(http, joojObjectMapper,
                anthropic.getBaseUrl(), auth);
    }
}
