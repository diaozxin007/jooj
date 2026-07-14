package com.xilidou.jooj.llm.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Vendor-neutral token usage summary.
 *
 * <p>Anthropic mapping:
 * <pre>
 *   input_tokens                 → inputTokens
 *   output_tokens                → outputTokens
 *   cache_creation_input_tokens  → cacheCreationInputTokens
 *   cache_read_input_tokens      → cacheReadInputTokens
 *   (n/a)                        → reasoningTokens
 * </pre>
 *
 * <p>OpenAI Chat Completions mapping:
 * <pre>
 *   usage.prompt_tokens                             → inputTokens
 *   usage.completion_tokens                         → outputTokens
 *   usage.prompt_tokens_details.cached_tokens        → cacheReadInputTokens
 *   (n/a — automatic prefix cache has no write bill) → cacheCreationInputTokens
 *   usage.completion_tokens_details.reasoning_tokens → reasoningTokens (o1/o3/o4)
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsage {

    private int inputTokens;
    private int outputTokens;

    /** Anthropic-only for now; OpenAI leaves this null. */
    private Integer cacheCreationInputTokens;

    /** Reads: both Anthropic and OpenAI Chat report this. */
    private Integer cacheReadInputTokens;

    /** Reasoning tokens (OpenAI o1/o3/o4). Anthropic leaves this null. */
    private Integer reasoningTokens;

    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
