package com.xilidou.jooj.llm.domain;

/**
 * Vendor-neutral LLM message role.
 *
 * <p>{@code TOOL} is a first-class role — the canonical layer refuses to model
 * tool_result as "packed inside a user message". Anthropic adapter is responsible
 * for merging consecutive {@code TOOL} messages into a single Anthropic
 * {@code role=user} message containing N {@code tool_result} blocks on outbound.
 *
 * <p>See P2 plan §一 "TOOL 作为一等 role" for the decision.
 */
public enum LlmRole {
    USER,
    ASSISTANT,
    TOOL
}
