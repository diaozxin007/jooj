package com.xilidou.jooj.http;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI 配置(Chat Completions API)。
 *
 * <p>协议:{@code POST /v1/chat/completions} —— 通过 {@link com.xilidou.jooj.llm.adapter.OpenAiChatAdapter}
 * 与 canonical {@code LlmRequest / LlmResponse} 互转,由 {@link OpenAiHttpClient} 执行 HTTP。
 *
 * <p>{@link OpenAiProviderConfiguration}({@code @ConditionalOnExpression})决定是否注册 provider:
 * apiKey 为空则不启用,不会污染主流程。
 *
 * <p>使用方式:
 * <ol>
 *   <li>在 {@code ~/.jooj/.env} 里设 {@code OPENAI_API_KEY=sk-...}</li>
 *   <li>切主模型走 OpenAI:在 {@code ~/.jooj/.env} 设 {@code MODEL_ID=gpt-4o-mini},
 *       或者 {@code jooj.recovery.fallback-model=gpt-4o-mini} 让 fallback 命中它</li>
 *   <li>ModelRouter 按 model ID 前缀路由:{@code gpt-*} / {@code o1-*} / {@code o3-*} / {@code o4-*}
 *       都进 OpenAI provider</li>
 * </ol>
 *
 * <p><b>OpenAI 与 Anthropic 的差异</b>(见 P2 plan §七 Risks):
 * <ul>
 *   <li>无手动 cache 断点 —— {@link com.xilidou.jooj.llm.domain.CacheHint} 被 adapter
 *       silently drop;OpenAI 会自动 prefix caching(prompt ≥ 1024 tokens 触发)</li>
 *   <li>o1 / o3 / o4 reasoning model:{@code max_tokens} 变 {@code max_completion_tokens}、
 *       丢 {@code temperature}、{@code system} 折叠进第一条 user。Adapter 内部按 model
 *       前缀 dispatch,无需 caller 干预</li>
 *   <li>无 extended thinking 序列化:Anthropic → OpenAI 时 {@link com.xilidou.jooj.llm.domain.LlmThinking}
 *       被丢,单向不可逆(canonical 层已接受这个 tradeoff)</li>
 * </ul>
 */
@Data
@ConfigurationProperties("jooj.openai")
public class OpenAiProperties {

    /** API 根 URL,默认 https://api.openai.com。 */
    private String baseUrl = "https://api.openai.com";

    /**
     * OpenAI API Key(通过 {@code Authorization: Bearer <key>} header 发送)。
     * 为空则不注册该 provider。
     */
    private String apiKey = "";

    /**
     * 默认模型 ID。仅当用户没有显式设 {@code jooj.anthropic.model} 且想让 OpenAI 做主 provider
     * 时使用;通常留空 —— caller 传什么 model 就路由到哪个 provider。
     */
    private String defaultModel = "";
}
