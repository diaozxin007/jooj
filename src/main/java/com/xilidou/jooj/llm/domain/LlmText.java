package com.xilidou.jooj.llm.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Plain-text content block. Vendor-neutral counterpart of Anthropic {@code TextBlock}
 * and OpenAI Chat message content string.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class LlmText implements LlmContent {

    private String text;

    @Override
    public String getKind() {
        return "text";
    }
}
