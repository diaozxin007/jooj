package com.xilidou.jooj.llm.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A single vendor-neutral message in a conversation.
 *
 * <p>Compared to the legacy {@link com.xilidou.jooj.http.dto.MessageParam}:
 * <ul>
 *   <li>{@code content} is <b>always</b> a {@code List<LlmContent>} — never {@code String}.
 *       The plan (§一 "入站时把老 MessageParam.content:Object 全部规范成 List<LlmContent>")
 *       explicitly kills the {@code String | List} union to eliminate every
 *       {@code instanceof List<?>} branch downstream.</li>
 *   <li>{@link LlmRole#TOOL} is a first-class role — tool_result never packs into
 *       {@code role=user} internally. The Anthropic adapter is responsible for
 *       merging consecutive TOOL messages on outbound.</li>
 *   <li>{@code cacheHints} is a plain List — Anthropic adapter attaches
 *       {@code cache_control} to the referenced content-block indices; OpenAI
 *       adapter drops the list silently.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmMessage {

    private LlmRole role;

    private List<LlmContent> content = new ArrayList<>();

    private List<CacheHint> cacheHints = new ArrayList<>();

    public LlmMessage(LlmRole role, List<LlmContent> content) {
        this(role, content, new ArrayList<>());
    }

    // ── convenience factories ──────────────────────────────────

    /** Plain USER text (first-user-turn or continuation prompt). */
    public static LlmMessage userText(String text) {
        return new LlmMessage(LlmRole.USER, new ArrayList<>(List.of(new LlmText(text))));
    }

    /** ASSISTANT message with the given content list (e.g. text + tool_calls). */
    public static LlmMessage assistant(List<LlmContent> content) {
        return new LlmMessage(LlmRole.ASSISTANT, new ArrayList<>(content));
    }

    /** TOOL message wrapping N tool_result blocks from one turn's fan-out. */
    public static LlmMessage toolResults(List<LlmToolResult> results) {
        List<LlmContent> content = new ArrayList<>(results.size());
        content.addAll(results);
        return new LlmMessage(LlmRole.TOOL, content);
    }
}
