package com.xilidou.jooj.llm.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Vendor-neutral response from an LLM.
 *
 * <p>Semantics-equivalent to the legacy {@link com.xilidou.jooj.http.dto.CreateMessageResponse},
 * with:
 * <ul>
 *   <li>{@link #stopReason} as an enum instead of a raw string</li>
 *   <li>{@link #content} elements are canonical {@link LlmContent} sealed types</li>
 *   <li>The helpers {@link #needsToolExecution()}, {@link #toolCalls()}, {@link #firstText()}
 *       preserve loop-dispatch ergonomics</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {

    private String id;
    private String model;

    @Builder.Default
    private List<LlmContent> content = new ArrayList<>();

    private LlmStopReason stopReason;

    /** Non-null only for {@link LlmStopReason#STOP_SEQUENCE}. */
    private String stopSequence;

    private LlmUsage usage;

    /**
     * True when the model wants to invoke tools next; loop must dispatch tool calls
     * and produce a follow-up TOOL message.
     */
    public boolean needsToolExecution() {
        return stopReason == LlmStopReason.TOOL_CALLS;
    }

    /** All tool_call blocks in the response, in order. */
    public List<LlmToolCall> toolCalls() {
        if (content == null) return List.of();
        return content.stream()
                .filter(b -> b instanceof LlmToolCall)
                .map(b -> (LlmToolCall) b)
                .toList();
    }

    /** First plain-text block's text, or empty string. */
    public String firstText() {
        if (content == null) return "";
        return content.stream()
                .filter(b -> b instanceof LlmText)
                .map(b -> ((LlmText) b).getText())
                .findFirst()
                .orElse("");
    }

    public Optional<LlmUsage> usageOpt() {
        return Optional.ofNullable(usage);
    }
}
