package com.xilidou.jooj.llm.domain;

/**
 * Prompt cache TTL tier for {@link CacheHint}.
 *
 * <p>Anthropic maps to {@code cache_control: {"type":"ephemeral"}} (5m) or
 * {@code cache_control: {"type":"ephemeral","ttl":"1h"}} (1h).
 *
 * <p>OpenAI Chat Completions has no manual cache breakpoint — its adapter
 * silently drops cache hints (OpenAI uses automatic prefix caching for prompts
 * ≥1024 tokens).
 */
public enum CacheTier {
    EPHEMERAL_5M,
    EPHEMERAL_1H
}
