package com.xilidou.jooj.llm.domain;

/**
 * Vendor-neutral stop reason. Kept as an enum (not String) so downstream
 * {@code switch} statements are exhaustive and adapters must map every case.
 *
 * <p>Anthropic mapping ({@link com.xilidou.jooj.http.dto.StopReason}):
 * <pre>
 *   end_turn      → END_TURN
 *   tool_use      → TOOL_CALLS
 *   max_tokens    → MAX_TOKENS
 *   stop_sequence → STOP_SEQUENCE
 *   unknown/null  → UNKNOWN
 * </pre>
 *
 * <p>OpenAI Chat Completions {@code finish_reason}:
 * <pre>
 *   stop            → END_TURN
 *   tool_calls      → TOOL_CALLS
 *   length          → MAX_TOKENS
 *   content_filter  → REFUSAL
 *   function_call   → TOOL_CALLS  (legacy)
 *   null            → UNKNOWN
 * </pre>
 *
 * <p>{@link #REFUSAL} is new; Anthropic does not currently expose this shape.
 */
public enum LlmStopReason {
    END_TURN,
    TOOL_CALLS,
    MAX_TOKENS,
    STOP_SEQUENCE,
    REFUSAL,
    UNKNOWN
}
