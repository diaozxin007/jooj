package com.xilidou.jooj.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojProperties;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * DeepSeek provider 自动装配 —— 仅当 {@code jooj.deepseek.api-key} 配置为非空时激活。
 *
 * <p>DeepSeek 提供 Anthropic 兼容端点({@code https://api.deepseek.com/anthropic}),
 * 协议与 Anthropic Messages API 完全一致,因此直接复用 {@link AnthropicHttpClient}。
 *
 * <p>使用方式:在 {@code application.yml} 中配置:
 * <pre>
 * jooj:
 *   deepseek:
 *     api-key: ${DEEPSEEK_API_KEY}
 *     # base-url: https://api.deepseek.com/anthropic  (默认值,无需配置)
 * </pre>
 *
 * <p>配置后,所有 model ID 以 {@code "deepseek-"} 开头的请求(如 {@code deepseek-chat})
 * 将自动路由到 DeepSeek API。
 */
@Configuration
@ConditionalOnProperty(name = "jooj.deepseek.api-key")
public class DeepSeekProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekProviderConfig.class);

    @Bean
    public ModelProvider deepseekProvider(OkHttpClient http,
                                         ObjectMapper joojObjectMapper,
                                         JoojProperties props) {
        JoojProperties.DeepSeek cfg = props.getDeepseek();
        log.info("Registering DeepSeek provider: baseUrl={}, model={}",
                cfg.getBaseUrl(), cfg.getModel());

        return new AnthropicHttpClient(
                http,
                joojObjectMapper,
                cfg.getBaseUrl(),
                new ApiKeyAuth(cfg.getApiKey()),
                "deepseek",
                List.of("deepseek-")
        );
    }
}
