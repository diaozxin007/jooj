package com.xilidou.jooj.llm.adapter;

import com.xilidou.jooj.http.dto.ContentBlock;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ThinkingBlock;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import com.xilidou.jooj.http.dto.UnknownBlock;
import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmOpaque;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmThinking;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transitional shape-bridge — canonical {@link LlmContent} → wire {@link ContentBlock}
 * translation used by Step C consumers that still hold {@code List<MessageParam>}
 * histories (agent loop, subagent, memory).
 *
 * <p>Lives here for Steps C–F; deleted at the end of Step G when {@code SessionStore}
 * fully migrates to {@code List<LlmMessage>}.
 *
 * <p>Kept intentionally small: only the {@code content → wire ContentBlock} direction
 * is needed by the transitional callers (the reverse direction is covered by
 * {@link AnthropicAdapter#messageToDomain} which is already the canonical path).
 */
public final class AnthropicShapeBridge {

    private AnthropicShapeBridge() {}

    /**
     * Translate a list of canonical {@link LlmContent} blocks into wire
     * {@link ContentBlock} blocks. Intended for building the {@code content} field
     * of {@code MessageParam.assistant(...)} or the fallback path where an
     * assistant response is appended verbatim.
     *
     * <p>Dropping rules:
     * <ul>
     *   <li>{@link LlmToolResult} — dropped (should not appear in assistant slot)</li>
     *   <li>{@link LlmThinking} vendor != "anthropic" — dropped (non-Anthropic thinking
     *       cannot faithfully round-trip through Anthropic wire)</li>
     *   <li>{@link LlmOpaque} vendor != "anthropic" — dropped</li>
     * </ul>
     */
    public static List<ContentBlock> contentToWire(List<LlmContent> content) {
        if (content == null) return List.of();
        List<ContentBlock> out = new ArrayList<>(content.size());
        for (LlmContent c : content) {
            ContentBlock w = singleToWire(c);
            if (w != null) out.add(w);
        }
        return out;
    }

    private static ContentBlock singleToWire(LlmContent c) {
        if (c instanceof LlmText t) {
            return new TextBlock(t.getText());
        }
        if (c instanceof LlmToolCall tc) {
            return new ToolUseBlock(tc.getId(), tc.getName(), tc.getInput());
        }
        if (c instanceof LlmThinking th) {
            if ("anthropic".equalsIgnoreCase(th.getVendor())) {
                return new ThinkingBlock(th.getText(), th.getSignature());
            }
            return null;
        }
        if (c instanceof LlmOpaque op) {
            if ("anthropic".equalsIgnoreCase(op.getVendor())) {
                UnknownBlock ub = new UnknownBlock();
                Map<String, Object> props = new HashMap<>(op.getRaw() != null ? op.getRaw() : Map.of());
                if (op.getType() != null) props.put("type", op.getType());
                for (Map.Entry<String, Object> e : props.entrySet()) {
                    ub.setProperty(e.getKey(), e.getValue());
                }
                return ub;
            }
            return null;
        }
        // LlmToolResult is not valid in assistant slot — drop.
        return null;
    }
}
