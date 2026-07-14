package com.xilidou.jooj.http;

import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.llm.domain.LlmException;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;

import java.util.List;

/**
 * Provider adapter 契约 —— 每个 LLM provider(Anthropic / OpenAI / ...)实现此接口。
 *
 * <p>{@link ModelRouter} 根据 {@link #modelPrefixes()} 将请求路由到匹配的 provider。
 *
 * <p>典型实现:
 * <ul>
 *   <li>{@link AnthropicHttpClient} — 直连 Anthropic API(prefix: "claude-")</li>
 *   <li>DeepSeek — Anthropic 兼容协议,复用 AnthropicHttpClient(prefix: "deepseek-")</li>
 *   <li>未来: OpenAiHttpClient — 直连 OpenAI API(prefix: "gpt-", "o1-", "o3-", "o4-")</li>
 * </ul>
 *
 * <p><b>P2 迁移(vendor-neutral domain):</b>{@link #createMessage(LlmRequest)}
 * 是新的 canonical 签名。老的 {@link #createMessage(CreateMessageRequest)} 保留作
 * 兼容 shim,内部通过 {@link com.xilidou.jooj.llm.adapter.AnthropicAdapter} 做翻译。
 * 下游 caller(Steps C-G)按拓扑顺序改用 canonical 签名后即可删除老方法。
 */
public interface ModelProvider {

    /**
     * Provider 名称(用于日志 / 配置引用)。
     * 例: "anthropic", "openai"
     */
    String name();

    /**
     * 该 provider 支持的 model ID 前缀列表。
     * {@link ModelRouter} 遍历前缀做 {@code model.startsWith(prefix)} 匹配。
     * 例: ["claude-"] 匹配 "claude-sonnet-4-6", "claude-3-haiku-20240307"
     */
    List<String> modelPrefixes();

    /**
     * 发送消息请求到该 provider 的 LLM API (legacy Anthropic-shaped signature).
     *
     * <p><b>Deprecated in favor of {@link #createMessage(LlmRequest)}.</b> Retained
     * during the P2 migration so pre-canonical callers continue to compile;
     * removed once every subsystem has migrated (Steps C–G).
     *
     * @deprecated use {@link #createMessage(LlmRequest)}
     */
    @Deprecated
    CreateMessageResponse createMessage(CreateMessageRequest req);

    /**
     * Canonical vendor-neutral send. Default implementation not provided — every
     * provider must implement this (adapters are responsible for wire translation).
     *
     * @throws LlmException on any provider-side failure (kind-classified)
     */
    LlmResponse createMessage(LlmRequest req) throws LlmException;
}
