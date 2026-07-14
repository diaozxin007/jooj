package com.xilidou.jooj.http;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic / 兼容代理的 HTTP 配置。
 *
 * <p>字段简单(4 个 String),豁免三分法(参考 Mcp / Permission),直接由
 * {@link HttpClientConfiguration} 消费。
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.Anthropic} 拆出,前缀 {@code jooj.anthropic}
 * 保持不变。归 {@code http/} 包与 {@link HttpClientConfiguration} 同居 —— 这是 HTTP
 * 出站客户端的配置,不属于哪个业务子系统。
 */
@Data
@ConfigurationProperties("jooj.anthropic")
public class AnthropicProperties {

    /** API 根 URL,默认 https://api.anthropic.com。 */
    private String baseUrl = "https://api.anthropic.com";

    /** Anthropic 官方 API Key(x-api-key header)。与 authToken 二选一。 */
    private String apiKey = "";

    /** 公司代理 / 兼容供应商的 Bearer Token(Authorization header)。与 apiKey 二选一。 */
    private String authToken = "";

    /** 模型 ID,如 {@code claude-sonnet-4-6}。 */
    private String model = "";
}
