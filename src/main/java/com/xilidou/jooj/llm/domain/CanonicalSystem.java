package com.xilidou.jooj.llm.domain;

import java.util.List;

/**
 * Canonical system prompt bundle: system content blocks + independent cache hints.
 *
 * <p>Prompt-cache placement is a vendor-specific concern. The canonical
 * representation keeps the pure text content ({@link LlmContent}) separate from
 * cache metadata ({@link CacheHint}), so each provider adapter decides how (or
 * whether) to translate hints:
 * <ul>
 *   <li>{@code AnthropicAdapter}:每个 {@link CacheHint#index} 对应的 content
 *       block 转 wire 时贴 {@code cache_control} 字段</li>
 *   <li>{@code OpenAiChatAdapter}:忽略 hints(OpenAI 自动 prefix caching,≥1024
 *       token 自动命中,无需手动断点)</li>
 * </ul>
 *
 * <p>Produced by {@code SystemPromptAssembler.assembleCanonical} and consumed by
 * {@code AgentLoopHarness} — replaces the P2-era wire {@code SystemTextBlock} +
 * manual index bookkeeping bridge.
 *
 * @param content system content blocks, index i corresponds to i-th block
 * @param hints   optional cache breakpoints, hint.index() points into {@code content}
 */
public record CanonicalSystem(List<LlmContent> content, List<CacheHint> hints) {
    public CanonicalSystem {
        if (content == null) content = List.of();
        if (hints == null) hints = List.of();
    }
}
