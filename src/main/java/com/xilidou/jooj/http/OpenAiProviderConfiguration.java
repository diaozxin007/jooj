package com.xilidou.jooj.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAI Chat Completions provider 自动装配 —— 仅当 {@code jooj.openai.api-key}
 * 非空时激活(P2 Step H)。
 *
 * <p>用 {@link ConditionalOnExpression} 而非 {@code @ConditionalOnProperty}:
 * 后者对空串的判定是"property 存在" → 会误激活;{@code SpEL} 显式检查 {@code !isEmpty()},
 * 空串 / 未设都不激活。这跟 {@link DeepSeekProviderConfiguration} 保持一致。
 *
 * <p>启用后:model ID 以 {@code gpt-} / {@code o1-} / {@code o3-} / {@code o4-} /
 * {@code chatgpt-} 开头的请求由 {@link ModelRouter} 自动路由到 OpenAI provider。
 *
 * <p>使用方式:在 {@code ~/.jooj/.env}(用户级 secret 文件)中配置:
 * <pre>
 * OPENAI_API_KEY=sk-your-key-here
 * # OPENAI_BASE_URL=https://api.openai.com   (默认值,无需覆盖)
 * # OPENAI_DEFAULT_MODEL=gpt-4o-mini          (仅当想让 OpenAI 做主 provider 时才设)
 * </pre>
 *
 * <p><b>常见场景</b>:
 * <ul>
 *   <li><b>Anthropic 主 + OpenAI fallback</b>:{@code MODEL_ID=claude-sonnet-4-6} +
 *       {@code FALLBACK_MODEL_ID=gpt-4o-mini}。连续 529 触发 fallback 后自动跨 provider 路由。</li>
 *   <li><b>OpenAI 单跑</b>:{@code MODEL_ID=gpt-4o-mini} + {@code ANTHROPIC_API_KEY=} 留空。</li>
 *   <li><b>o1/o3 reasoning 模式</b>:直接 {@code MODEL_ID=o3-mini}。Adapter 内部按 model
 *       前缀 dispatch,自动:{@code max_tokens → max_completion_tokens}、丢
 *       {@code temperature}、{@code system} 折叠进第一条 user。</li>
 * </ul>
 */
@Configuration
@ConditionalOnExpression("!'${jooj.openai.api-key:}'.isEmpty()")
public class OpenAiProviderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProviderConfiguration.class);

    @Bean
    public ModelProvider openaiProvider(OkHttpClient http,
                                        ObjectMapper joojObjectMapper,
                                        OpenAiProperties cfg) {
        log.info("Registering OpenAI provider: baseUrl={}, defaultModel={}",
                cfg.getBaseUrl(),
                cfg.getDefaultModel().isEmpty() ? "(none)" : cfg.getDefaultModel());
        return new OpenAiHttpClient(
                http,
                joojObjectMapper,
                cfg.getBaseUrl(),
                new BearerTokenAuth(cfg.getApiKey())
        );
    }
}
