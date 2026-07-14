package com.xilidou.jooj.http;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DeepSeek 配置(Anthropic 兼容模式)。
 *
 * <p>使用 {@code https://api.deepseek.com/anthropic} 端点,协议与 Anthropic 完全相同,
 * 因此复用 {@link com.xilidou.jooj.http.AnthropicHttpClient} 收发消息,只需替换
 * baseUrl / apiKey / model 三项。
 *
 * <p>{@link DeepSeekProviderConfiguration}({@code @ConditionalOnExpression})决定是否注册 provider:
 * apiKey 为空则不启用,不会污染主流程。
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.DeepSeek} 拆出,前缀 {@code jooj.deepseek}
 * 保持不变。
 */
@Data
@ConfigurationProperties("jooj.deepseek")
public class DeepSeekProperties {

    /** API 根 URL,默认 https://api.deepseek.com/anthropic。 */
    private String baseUrl = "https://api.deepseek.com/anthropic";

    /** DeepSeek API Key(通过 x-api-key header 发送)。为空则不注册该 provider。 */
    private String apiKey = "";

    /** 模型 ID,如 {@code deepseek-chat}。 */
    private String model = "";
}
