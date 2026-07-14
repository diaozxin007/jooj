package com.xilidou.jooj.llm.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Forward-compat carrier for unknown / vendor-specific content shapes.
 *
 * <p>Replaces {@link com.xilidou.jooj.http.dto.UnknownBlock}. When Anthropic (or
 * a future OpenAI Responses adapter) emits a block type we don't yet model,
 * Jackson lands it here so serialization round-trips without losing bytes.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code vendor} — the emitting provider (e.g. {@code "anthropic"} / {@code "openai"})</li>
 *   <li>{@code type} — the raw wire type discriminator (e.g. {@code "image"} / {@code "mcp_tool_use"})</li>
 *   <li>{@code raw} — every other field on the block, preserved verbatim</li>
 * </ul>
 *
 * <p>Adapters may filter these out (OpenAI adapter drops all opaque blocks) or
 * pass them through (Anthropic adapter keeps ones with {@code vendor="anthropic"}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class LlmOpaque implements LlmContent {

    private String vendor;

    private String type;

    private Map<String, Object> raw = new HashMap<>();

    @Override
    public String getKind() {
        return "opaque";
    }
}
