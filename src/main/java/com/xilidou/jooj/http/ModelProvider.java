package com.xilidou.jooj.http;

import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;

import java.util.List;

/**
 * Provider adapter 契约 —— 每个 LLM provider(Anthropic / OpenAI / ...)实现此接口。
 *
 * <p>{@link ModelRouter} 根据 {@link #modelPrefixes()} 将请求路由到匹配的 provider。
 *
 * <p>典型实现:
 * <ul>
 *   <li>{@link AnthropicHttpClient} — 直连 Anthropic API(prefix: "claude-")</li>
 *   <li>未来: OpenAiAdapter — 直连 OpenAI API(prefix: "gpt-", "o1-")</li>
 * </ul>
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
     * 发送消息请求到该 provider 的 LLM API。
     * 语义与 {@link AnthropicClient#createMessage(CreateMessageRequest)} 相同。
     */
    CreateMessageResponse createMessage(CreateMessageRequest req);
}
