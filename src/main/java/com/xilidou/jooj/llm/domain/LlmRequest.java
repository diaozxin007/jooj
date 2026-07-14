package com.xilidou.jooj.llm.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Vendor-neutral request to an LLM.
 *
 * <p>The {@code system} field is a {@code List<LlmContent>}:
 * <ul>
 *   <li>Single-element list with an {@link LlmText} → traditional single system prompt.
 *       Anthropic adapter serializes as {@code system: "<text>"} (compact wire form).</li>
 *   <li>Multi-element or has {@link #systemCacheHints} → Anthropic adapter serializes as
 *       {@code system: [{"type":"text","text":"...","cache_control":{...}}, ...]}.</li>
 *   <li>OpenAI adapter concatenates all system texts and emits a {@code role:"system"}
 *       first message (or folds into the first user message for o1/o3/o4 models).</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {

    /** Model ID (e.g. {@code claude-sonnet-4-6}, {@code gpt-4o-mini}). */
    private String model;

    /** Max output tokens. Anthropic {@code max_tokens} / OpenAI {@code max_tokens} (or {@code max_completion_tokens} for o-series). */
    private Integer maxTokens;

    /** Sampling temperature. Silently dropped by OpenAI o-series adapters. */
    private Double temperature;

    /** Optional stop sequences. */
    @Builder.Default
    private List<String> stopSequences = new ArrayList<>();

    /** System prompt content, in blocks. Adapters decide wire encoding (see class javadoc). */
    @Builder.Default
    private List<LlmContent> system = new ArrayList<>();

    /** Cache hints attached to {@link #system}. */
    @Builder.Default
    private List<CacheHint> systemCacheHints = new ArrayList<>();

    /** Conversation history. */
    @Builder.Default
    private List<LlmMessage> messages = new ArrayList<>();

    /** Tool definitions. Empty means "no tools available". */
    @Builder.Default
    private List<LlmToolDef> tools = new ArrayList<>();

    // ── convenience builders ────────────────────────────────────

    /** Convenience: set {@link #system} from a single system text. */
    public static LlmRequest.LlmRequestBuilder builderWithSystemText(String systemText) {
        return LlmRequest.builder().system(List.of(new LlmText(systemText)));
    }

    /** Empty list if null (defensive; adapters call this). */
    public List<LlmMessage> messagesOrEmpty() {
        return messages != null ? messages : Collections.emptyList();
    }

    /** Empty list if null (defensive; adapters call this). */
    public List<LlmToolDef> toolsOrEmpty() {
        return tools != null ? tools : Collections.emptyList();
    }
}
