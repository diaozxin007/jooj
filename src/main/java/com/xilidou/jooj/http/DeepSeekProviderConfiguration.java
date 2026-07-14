package com.xilidou.jooj.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojProperties;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * DeepSeek provider 自动装配 —— 仅当 {@code jooj.deepseek.api-key} 非空时激活。
 *
 * <p>用 {@link ConditionalOnExpression} 而非 {@code @ConditionalOnProperty}:
 * 后者对空串的判定是"property 存在" → 会误激活;{@code SpEL} 显式检查
 * {@code !isEmpty()},空串 / 未设都不激活。
 *
 * <p>DeepSeek 提供 Anthropic 兼容端点({@code https://api.deepseek.com/anthropic}),
 * 协议与 Anthropic Messages API 完全一致,因此直接复用 {@link AnthropicHttpClient}。
 *
 * <p>使用方式:在 {@code ~/.jooj/.env}(用户级 secret 文件)中配置:
 * <pre>
 * DEEPSEEK_API_KEY=your-api-key-here
 * # DEEPSEEK_BASE_URL=https://api.deepseek.com/anthropic   (默认值,无需覆盖)
 * # DEEPSEEK_MODEL=deepseek-chat                            (默认值)
 * </pre>
 *
 * <p>{@code application.yml} 里的 {@code jooj.deepseek.*} 通过 {@code ${DEEPSEEK_*:default}}
 * 占位符引用这些环境变量;首次启动时 {@link com.xilidou.jooj.bootstrap.JoojEnvBootstrap}
 * 会自动生成 {@code ~/.jooj/.env} 模板,里面已含 DeepSeek section,解开注释填 key 即可。
 *
 * <p>配置后,所有 model ID 以 {@code "deepseek-"} 开头的请求(如 {@code deepseek-chat})
 * 将自动路由到 DeepSeek API。
 */
@Configuration
@ConditionalOnExpression("!'${jooj.deepseek.api-key:}'.isEmpty()")
public class DeepSeekProviderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekProviderConfiguration.class);

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
