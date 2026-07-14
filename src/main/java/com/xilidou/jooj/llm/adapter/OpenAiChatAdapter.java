package com.xilidou.jooj.llm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmErrorKind;
import com.xilidou.jooj.llm.domain.LlmException;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmOpaque;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;
import com.xilidou.jooj.llm.domain.LlmRole;
import com.xilidou.jooj.llm.domain.LlmStopReason;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmThinking;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolDef;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import com.xilidou.jooj.llm.domain.LlmUsage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure mapping between canonical LLM domain types and OpenAI Chat Completions
 * wire shape ({@code POST /v1/chat/completions}).
 *
 * <p>Wire is represented as {@link JsonNode} here rather than typed DTOs — the
 * OpenAI HTTP client (introduced in Step H) will consume {@code toWire} output
 * directly as the HTTP body and pass the response JSON to {@code toDomain}. This
 * keeps Step A pure additive (no wire-DTO tree required just to declare the
 * mapping shape).
 *
 * <p>Model-family behaviors (per P2 plan §二 "OpenAiChatAdapter"):
 * <ul>
 *   <li>{@code o1-} / {@code o3-} / {@code o4-}: {@code max_tokens} becomes
 *       {@code max_completion_tokens}; {@code temperature} is dropped;
 *       {@code system} is folded into the first user message.</li>
 *   <li>Others (gpt-4o, gpt-4-turbo, gpt-3.5-turbo, ...): plain Chat Completions.</li>
 * </ul>
 *
 * <p>Dropped fields (silently):
 * <ul>
 *   <li>{@link com.xilidou.jooj.llm.domain.CacheHint} — OpenAI does automatic prefix caching.</li>
 *   <li>{@link LlmThinking} — Chat Completions has no thinking-block shape.</li>
 *   <li>{@link LlmOpaque} with {@code vendor != "openai"} — Anthropic thinking / unknown blocks are lossy on this hop.</li>
 * </ul>
 */
public final class OpenAiChatAdapter {

    private final ObjectMapper json;

    public OpenAiChatAdapter(ObjectMapper json) {
        this.json = json;
    }

    // ────────────────────────────────────────────────────────────
    //  toWire — canonical → OpenAI Chat Completions JSON body
    // ────────────────────────────────────────────────────────────

    /** Build an OpenAI Chat Completions request body from a canonical request. */
    public JsonNode toWire(LlmRequest req) {
        ObjectNode root = json.createObjectNode();
        String model = req.getModel();
        root.put("model", model);

        boolean isReasoning = isReasoningModel(model);

        // token cap
        if (req.getMaxTokens() != null) {
            root.put(isReasoning ? "max_completion_tokens" : "max_tokens", req.getMaxTokens());
        }
        // temperature (dropped for reasoning models)
        if (!isReasoning && req.getTemperature() != null) {
            root.put("temperature", req.getTemperature());
        }
        // stop
        if (req.getStopSequences() != null && !req.getStopSequences().isEmpty()) {
            ArrayNode stop = root.putArray("stop");
            req.getStopSequences().forEach(stop::add);
        }

        // messages
        ArrayNode messages = root.putArray("messages");
        String systemText = concatSystemText(req.getSystem());
        if (systemText != null && !systemText.isEmpty()) {
            if (isReasoning) {
                // fold into first user message — handled below
            } else {
                ObjectNode sys = messages.addObject();
                sys.put("role", "system");
                sys.put("content", systemText);
            }
        }

        boolean firstUserWritten = false;
        for (LlmMessage m : req.messagesOrEmpty()) {
            switch (m.getRole()) {
                case USER -> {
                    ObjectNode um = messages.addObject();
                    um.put("role", "user");
                    String userText = extractPlainText(m.getContent());
                    if (isReasoning && !firstUserWritten && systemText != null && !systemText.isEmpty()) {
                        userText = systemText + "\n\n" + (userText == null ? "" : userText);
                    }
                    um.put("content", userText == null ? "" : userText);
                    firstUserWritten = true;
                }
                case ASSISTANT -> {
                    ObjectNode am = messages.addObject();
                    am.put("role", "assistant");
                    String text = extractPlainText(m.getContent());
                    if (text != null && !text.isEmpty()) {
                        am.put("content", text);
                    } else {
                        am.putNull("content");
                    }
                    List<LlmToolCall> calls = m.getContent().stream()
                            .filter(c -> c instanceof LlmToolCall)
                            .map(c -> (LlmToolCall) c)
                            .toList();
                    if (!calls.isEmpty()) {
                        ArrayNode toolCalls = am.putArray("tool_calls");
                        for (LlmToolCall tc : calls) {
                            ObjectNode call = toolCalls.addObject();
                            call.put("id", tc.getId());
                            call.put("type", "function");
                            ObjectNode fn = call.putObject("function");
                            fn.put("name", tc.getName());
                            fn.put("arguments", writeArguments(tc.getInput()));
                        }
                    }
                }
                case TOOL -> {
                    // Emit one role=tool message per LlmToolResult in this LlmMessage.
                    for (LlmContent c : m.getContent()) {
                        if (c instanceof LlmToolResult r) {
                            ObjectNode tm = messages.addObject();
                            tm.put("role", "tool");
                            tm.put("tool_call_id", r.getToolCallId());
                            tm.put("content", r.getOutput() == null ? "" : r.getOutput());
                        }
                        // Ignore text blocks in TOOL messages (background notifications
                        // are not a Chat Completions concept; upstream should merge them
                        // into the following USER turn if desired).
                    }
                }
            }
        }

        // tools
        List<LlmToolDef> tools = req.toolsOrEmpty();
        if (!tools.isEmpty()) {
            ArrayNode t = root.putArray("tools");
            for (LlmToolDef def : tools) {
                ObjectNode tn = t.addObject();
                tn.put("type", "function");
                ObjectNode fn = tn.putObject("function");
                fn.put("name", def.getName());
                fn.put("description", def.getDescription() == null ? "" : def.getDescription());
                JsonNode schema = def.getSchema();
                if (schema != null) {
                    fn.set("parameters", schema);
                } else {
                    fn.set("parameters", json.createObjectNode().put("type", "object"));
                }
            }
        }

        return root;
    }

    /** Serialize a tool-call {@code input} JsonNode to the OpenAI-expected string form. */
    private String writeArguments(JsonNode input) {
        try {
            return input == null ? "{}" : json.writeValueAsString(input);
        } catch (IOException e) {
            return "{}";
        }
    }

    // ────────────────────────────────────────────────────────────
    //  toDomain — OpenAI Chat Completions JSON → canonical
    // ────────────────────────────────────────────────────────────

    /** Parse an OpenAI Chat Completions response body into an {@link LlmResponse}. */
    public LlmResponse toDomain(JsonNode wire) {
        LlmResponse.LlmResponseBuilder b = LlmResponse.builder();
        b.id(textOrNull(wire, "id"));
        b.model(textOrNull(wire, "model"));

        JsonNode choices = wire.get("choices");
        List<LlmContent> content = new ArrayList<>();
        LlmStopReason stopReason = LlmStopReason.UNKNOWN;
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode choice = choices.get(0);
            JsonNode message = choice.get("message");
            if (message != null) {
                JsonNode text = message.get("content");
                if (text != null && !text.isNull() && !text.asText().isEmpty()) {
                    content.add(new LlmText(text.asText()));
                }
                JsonNode toolCalls = message.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        String id = textOrNull(tc, "id");
                        JsonNode fn = tc.get("function");
                        if (fn == null) continue;
                        String name = textOrNull(fn, "name");
                        JsonNode argsNode = fn.get("arguments");
                        JsonNode parsedArgs = parseArguments(argsNode);
                        content.add(new LlmToolCall(id, name, parsedArgs));
                    }
                }
            }
            String finishReason = textOrNull(choice, "finish_reason");
            stopReason = finishReasonToDomain(finishReason);
        }
        b.content(content);
        b.stopReason(stopReason);

        JsonNode usage = wire.get("usage");
        if (usage != null) {
            LlmUsage.LlmUsageBuilder ub = LlmUsage.builder()
                    .inputTokens(intOr(usage, "prompt_tokens", 0))
                    .outputTokens(intOr(usage, "completion_tokens", 0));
            JsonNode ptd = usage.get("prompt_tokens_details");
            if (ptd != null && ptd.hasNonNull("cached_tokens")) {
                ub.cacheReadInputTokens(ptd.get("cached_tokens").asInt());
            }
            JsonNode ctd = usage.get("completion_tokens_details");
            if (ctd != null && ctd.hasNonNull("reasoning_tokens")) {
                ub.reasoningTokens(ctd.get("reasoning_tokens").asInt());
            }
            b.usage(ub.build());
        }

        return b.build();
    }

    private LlmStopReason finishReasonToDomain(String reason) {
        if (reason == null) return LlmStopReason.UNKNOWN;
        return switch (reason) {
            case "stop" -> LlmStopReason.END_TURN;
            case "tool_calls", "function_call" -> LlmStopReason.TOOL_CALLS;
            case "length" -> LlmStopReason.MAX_TOKENS;
            case "content_filter" -> LlmStopReason.REFUSAL;
            default -> LlmStopReason.UNKNOWN;
        };
    }

    /** Parse OpenAI tool_call.arguments (a JSON-encoded string) into a JsonNode. */
    private JsonNode parseArguments(JsonNode argsNode) {
        if (argsNode == null || argsNode.isNull()) {
            return json.createObjectNode();
        }
        if (argsNode.isTextual()) {
            try {
                return json.readTree(argsNode.asText());
            } catch (IOException e) {
                return json.createObjectNode();
            }
        }
        // Already structured (some SDKs send an object directly) — use as-is
        return argsNode;
    }

    // ────────────────────────────────────────────────────────────
    //  Error classification (called by OpenAiHttpClient in Step H)
    // ────────────────────────────────────────────────────────────

    /**
     * Classify an OpenAI failure into a canonical {@link LlmException}.
     *
     * <p>Rules per P2 plan §二 "OpenAiChatAdapter · 错误分类":
     * <ul>
     *   <li>body {@code error.code = context_length_exceeded} → PROMPT_TOO_LONG</li>
     *   <li>429 {@code error.type = insufficient_quota} → BAD_REQUEST</li>
     *   <li>429 (other) → RATE_LIMITED</li>
     *   <li>500-503 → OVERLOADED</li>
     *   <li>401 → AUTH</li>
     *   <li>0 → IO_ERROR</li>
     *   <li>other 4xx → BAD_REQUEST</li>
     *   <li>else → UNKNOWN</li>
     * </ul>
     */
    public LlmException classify(int status, String body, Throwable cause) {
        String lowerBody = body == null ? "" : body.toLowerCase(Locale.ROOT);
        LlmErrorKind kind;

        boolean contextLenExceeded = lowerBody.contains("\"code\": \"context_length_exceeded\"")
                || lowerBody.contains("\"code\":\"context_length_exceeded\"")
                || lowerBody.contains("context_length_exceeded");
        boolean insufficientQuota = lowerBody.contains("insufficient_quota");

        if (status == 0) {
            kind = LlmErrorKind.IO_ERROR;
        } else if (contextLenExceeded) {
            kind = LlmErrorKind.PROMPT_TOO_LONG;
        } else if (status == 401) {
            kind = LlmErrorKind.AUTH;
        } else if (status == 429 && insufficientQuota) {
            kind = LlmErrorKind.BAD_REQUEST;
        } else if (status == 429) {
            kind = LlmErrorKind.RATE_LIMITED;
        } else if (status >= 500 && status <= 503) {
            kind = LlmErrorKind.OVERLOADED;
        } else if (status >= 400 && status < 500) {
            kind = LlmErrorKind.BAD_REQUEST;
        } else {
            kind = LlmErrorKind.UNKNOWN;
        }
        return cause != null
                ? new LlmException(kind, status, body == null ? "" : body, cause)
                : new LlmException(kind, status, body == null ? "" : body);
    }

    // ────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────

    static boolean isReasoningModel(String model) {
        if (model == null) return false;
        return model.startsWith("o1-") || model.startsWith("o3-") || model.startsWith("o4-")
                || model.equals("o1") || model.equals("o3") || model.equals("o4");
    }

    private static String concatSystemText(List<LlmContent> system) {
        if (system == null || system.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (LlmContent c : system) {
            if (c instanceof LlmText t && t.getText() != null) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(t.getText());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Concatenate all {@link LlmText} blocks in a message's content list. */
    private static String extractPlainText(List<LlmContent> content) {
        if (content == null) return null;
        StringBuilder sb = new StringBuilder();
        for (LlmContent c : content) {
            if (c instanceof LlmText t && t.getText() != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(t.getText());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String textOrNull(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static int intOr(JsonNode n, String field, int fallback) {
        if (n == null) return fallback;
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? fallback : v.asInt(fallback);
    }
}
