package com.xilidou.jooj.llm.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The result of a tool invocation.
 *
 * <p>Only appears in messages with {@link LlmRole#TOOL}. Anthropic adapter merges
 * these into a single {@code role=user} message with N {@code tool_result} blocks
 * on outbound; OpenAI adapter emits one {@code role=tool} message per result.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code toolCallId} — must equal a preceding {@link LlmToolCall#id}</li>
 *   <li>{@code output} — plain-text output (P2 plan §一 boundary #4:
 *       no vision support in this refactor; output stays {@code String})</li>
 *   <li>{@code isError} — {@code true} when the tool failed (Anthropic maps to
 *       {@code is_error} on the block; OpenAI stringifies to content since Chat
 *       Completions has no dedicated error flag)</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class LlmToolResult implements LlmContent {

    private String toolCallId;

    private String output;

    private boolean isError;

    @Override
    public String getKind() {
        return "tool_result";
    }

    /** Convenience: success result. */
    public static LlmToolResult success(String toolCallId, String output) {
        return new LlmToolResult(toolCallId, output, false);
    }

    /** Convenience: error result. */
    public static LlmToolResult error(String toolCallId, String output) {
        return new LlmToolResult(toolCallId, output, true);
    }
}
