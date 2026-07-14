package com.xilidou.jooj.llm.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Vendor-specific extended-thinking / reasoning content.
 *
 * <p>The {@code vendor} field identifies which provider originally emitted this
 * block. Adapters use it to decide fidelity:
 * <ul>
 *   <li>Anthropic adapter, {@code vendor="anthropic"} → round-tripped verbatim
 *       (as an Anthropic {@code thinking} block with its {@code signature})</li>
 *   <li>OpenAI adapter → dropped silently (Chat Completions has no thinking-block
 *       shape; the tradeoff is documented in P2 plan §七)</li>
 * </ul>
 *
 * <p>Cross-provider thinking is inherently lossy; this design accepts that.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class LlmThinking implements LlmContent {

    private String text;

    /** Provider-issued signature (Anthropic requires this to round-trip on next turn). */
    private String signature;

    /** {@code "anthropic"} for now; string keeps the door open for other providers. */
    private String vendor;

    @Override
    public String getKind() {
        return "thinking";
    }
}
