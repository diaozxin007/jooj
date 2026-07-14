package com.xilidou.jooj.llm.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Vendor-neutral content block for an {@link LlmMessage}.
 *
 * <p>Sealed hierarchy — 5 known types:
 * <ul>
 *   <li>{@link LlmText}       — plain text (any role)</li>
 *   <li>{@link LlmToolCall}   — assistant requesting a tool</li>
 *   <li>{@link LlmToolResult} — result of a tool invocation (TOOL role only)</li>
 *   <li>{@link LlmThinking}   — extended thinking / reasoning (vendor-specific)</li>
 *   <li>{@link LlmOpaque}     — forward-compat carrier for unknown block shapes</li>
 * </ul>
 *
 * <p>Jackson dispatches by {@code kind} property. {@code visible = true} + explicit
 * {@code allowGetters} mirrors the Anthropic {@link com.xilidou.jooj.http.dto.ContentBlock}
 * defensive dance (see that class's javadoc for why).
 *
 * <p>{@link LlmOpaque} is the default fallback — future new content types round-trip
 * without breaking session deserialization.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "kind",
        visible = true,
        defaultImpl = LlmOpaque.class
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = LlmText.class,       name = "text"),
        @JsonSubTypes.Type(value = LlmToolCall.class,   name = "tool_call"),
        @JsonSubTypes.Type(value = LlmToolResult.class, name = "tool_result"),
        @JsonSubTypes.Type(value = LlmThinking.class,   name = "thinking"),
        @JsonSubTypes.Type(value = LlmOpaque.class,     name = "opaque"),
})
@JsonIgnoreProperties(value = {"kind"}, allowGetters = true)
public sealed interface LlmContent
        permits LlmText, LlmToolCall, LlmToolResult, LlmThinking, LlmOpaque {

    /**
     * Discriminator: {@code "text"} / {@code "tool_call"} / {@code "tool_result"} /
     * {@code "thinking"} / {@code "opaque"}.
     */
    String getKind();
}
