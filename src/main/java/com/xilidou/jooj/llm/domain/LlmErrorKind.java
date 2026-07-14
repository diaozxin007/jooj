package com.xilidou.jooj.llm.domain;

/**
 * Provider-neutral classification of LLM API failures.
 *
 * <p>{@link com.xilidou.jooj.agent.RecoveryCoordinator} dispatches on this enum
 * instead of parsing raw error message strings — that keeps the coordinator
 * uncoupled from provider-specific error body shapes.
 *
 * <p>Each provider's adapter is responsible for classifying its wire errors into
 * one of these buckets (see P2 plan §二 "错误分类" for the mapping table).
 */
public enum LlmErrorKind {
    /** Request exceeded model context window. Triggers L4 reactive_compact. */
    PROMPT_TOO_LONG,

    /** Rate-limit (429 without insufficient_quota). Retryable with backoff. */
    RATE_LIMITED,

    /** Provider overloaded (5xx / Anthropic 529). Retryable with backoff. */
    OVERLOADED,

    /** Auth failure (401 / bad API key / expired token). Not retryable. */
    AUTH,

    /** Client-side error (400 not matching PROMPT_TOO_LONG, insufficient_quota, etc.). */
    BAD_REQUEST,

    /** Network / socket / timeout error. Retryable. */
    IO_ERROR,

    /** Unclassified; fall through to fatal-fallback path. */
    UNKNOWN
}
