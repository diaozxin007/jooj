package com.xilidou.jooj.llm.domain;

/**
 * Vendor-neutral exception for LLM API failures.
 *
 * <p>Replaces {@link com.xilidou.jooj.http.AnthropicException} at the domain
 * boundary: adapters catch provider-specific exceptions, classify them, and rethrow
 * as this type. {@link com.xilidou.jooj.agent.RecoveryCoordinator} sees only
 * {@link LlmErrorKind}, never a status-code + body string pair.
 *
 * <p>{@code statusCode} is retained for observability (log lines, error dashboards)
 * but must not participate in dispatch decisions.
 */
public class LlmException extends RuntimeException {

    private final LlmErrorKind kind;
    private final int statusCode;

    public LlmException(LlmErrorKind kind, int statusCode, String message) {
        super(buildMessage(kind, statusCode, message));
        this.kind = kind;
        this.statusCode = statusCode;
    }

    public LlmException(LlmErrorKind kind, int statusCode, String message, Throwable cause) {
        super(buildMessage(kind, statusCode, message), cause);
        this.kind = kind;
        this.statusCode = statusCode;
    }

    public LlmErrorKind getKind() {
        return kind;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /** Convenience — {@link LlmErrorKind#RATE_LIMITED} / {@link LlmErrorKind#OVERLOADED} / {@link LlmErrorKind#IO_ERROR} are retryable. */
    public boolean isRetryable() {
        return kind == LlmErrorKind.RATE_LIMITED
                || kind == LlmErrorKind.OVERLOADED
                || kind == LlmErrorKind.IO_ERROR;
    }

    /** Convenience — {@link LlmErrorKind#PROMPT_TOO_LONG}. */
    public boolean isPromptTooLong() {
        return kind == LlmErrorKind.PROMPT_TOO_LONG;
    }

    private static String buildMessage(LlmErrorKind kind, int statusCode, String message) {
        if (statusCode == 0) {
            return "LLM " + kind + ": " + message;
        }
        return "LLM " + kind + " (HTTP " + statusCode + "): " + message;
    }
}
