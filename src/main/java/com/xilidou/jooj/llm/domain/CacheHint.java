package com.xilidou.jooj.llm.domain;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * A hint that a cache breakpoint should be placed at content-block index {@code index}
 * of the containing message / system list.
 *
 * <p>Anthropic adapter translates this into the corresponding block's
 * {@code cache_control} field. OpenAI adapter silently drops it.
 *
 * <p>Placement rules:
 * <ul>
 *   <li>Attached to {@code LlmRequest.systemCacheHints} — points into the system content list</li>
 *   <li>Attached to a single {@code LlmMessage.cacheHints} — points into that message's content list</li>
 * </ul>
 */
@Data
@AllArgsConstructor
public class CacheHint {
    /** Zero-based index into the containing content list. */
    private int index;
    /** TTL tier for the breakpoint. */
    private CacheTier tier;
}
