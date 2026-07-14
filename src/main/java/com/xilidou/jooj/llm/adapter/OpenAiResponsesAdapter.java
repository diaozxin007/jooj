package com.xilidou.jooj.llm.adapter;

import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;

/**
 * OpenAI Responses API adapter — placeholder for a future contribution.
 *
 * <p>P2 plan §一 boundary #3 explicitly scopes this refactor to
 * {@link OpenAiChatAdapter Chat Completions}. The Responses API (/v1/responses,
 * introduced 2024-Q4) offers a superset of Chat Completions with server-managed
 * conversation state, first-class agent primitives, and better multi-tool
 * dispatch semantics — likely a better long-term match for jooj's agent loop.
 *
 * <p>All methods currently throw {@link UnsupportedOperationException}. Wire this
 * up when Anthropic + Chat-Completions land is stable.
 */
public final class OpenAiResponsesAdapter {

    // TODO(P3): implement mapping to /v1/responses request/response shape.

    public Object toWire(LlmRequest req) {
        throw new UnsupportedOperationException(
                "OpenAI Responses API adapter is a placeholder; see P2 plan §二");
    }

    public LlmResponse toDomain(Object wire) {
        throw new UnsupportedOperationException(
                "OpenAI Responses API adapter is a placeholder; see P2 plan §二");
    }
}
