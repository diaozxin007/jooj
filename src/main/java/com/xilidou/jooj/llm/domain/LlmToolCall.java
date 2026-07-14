package com.xilidou.jooj.llm.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A request from the assistant to invoke a tool.
 *
 * <p>Only appears in messages with {@link LlmRole#ASSISTANT}.
 *
 * <p>Fields mirror Anthropic {@code tool_use} and OpenAI Chat {@code tool_calls[].function}:
 * <ul>
 *   <li>{@code id} — provider-issued call ID (e.g. Anthropic {@code toolu_xxx},
 *       OpenAI {@code call_xxx}); the matching {@link LlmToolResult#toolCallId} must equal this</li>
 *   <li>{@code name} — tool name</li>
 *   <li>{@code input} — tool arguments as JSON (Anthropic sends object, OpenAI sends
 *       stringified JSON that its adapter parses back to a {@code JsonNode})</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class LlmToolCall implements LlmContent {

    private String id;

    private String name;

    private JsonNode input;

    @Override
    public String getKind() {
        return "tool_call";
    }
}
