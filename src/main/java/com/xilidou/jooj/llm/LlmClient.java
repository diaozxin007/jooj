package com.xilidou.jooj.llm;

import com.xilidou.jooj.llm.domain.LlmException;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;

/**
 * Canonical vendor-neutral LLM client contract.
 *
 * <p>The successor to {@link com.xilidou.jooj.http.AnthropicClient} — implementations
 * accept the canonical {@link LlmRequest} shape and return canonical {@link LlmResponse}
 * or throw canonical {@link LlmException}.
 *
 * <p>{@link com.xilidou.jooj.http.ModelRouter} implements this alongside the
 * legacy Anthropic contract during the migration; downstream callers migrate to
 * {@code LlmClient} at their own pace (Steps C–G of the P2 plan).
 */
public interface LlmClient {

    /**
     * Send a canonical request; get a canonical response.
     *
     * @throws LlmException on any provider-side failure (kind-classified)
     */
    LlmResponse createMessage(LlmRequest req) throws LlmException;
}
