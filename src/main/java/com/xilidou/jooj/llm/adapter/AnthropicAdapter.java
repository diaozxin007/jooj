package com.xilidou.jooj.llm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.http.AnthropicException;
import com.xilidou.jooj.http.dto.CacheControl;
import com.xilidou.jooj.http.dto.ContentBlock;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.StopReason;
import com.xilidou.jooj.http.dto.SystemTextBlock;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ThinkingBlock;
import com.xilidou.jooj.http.dto.ToolDef;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import com.xilidou.jooj.http.dto.UnknownBlock;
import com.xilidou.jooj.http.dto.Usage;
import com.xilidou.jooj.llm.domain.CacheHint;
import com.xilidou.jooj.llm.domain.CacheTier;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure mapping between canonical LLM domain types and Anthropic wire DTOs.
 *
 * <p>Contains no IO — is safe to call from any layer. The Anthropic HTTP client
 * composes this adapter with an OkHttp call in Step B.
 *
 * <p>Round-trip contract:
 * <ol>
 *   <li>{@code toWire(LlmRequest)} → {@link CreateMessageRequest}</li>
 *   <li>{@code toDomain(CreateMessageResponse)} → {@link LlmResponse}</li>
 *   <li>{@code messageToDomain(MessageParam)} → {@link LlmMessage} (used when reading
 *       existing DTO history; enables the transitional Step B bridge)</li>
 *   <li>{@code messageToWire(LlmMessage)} → {@link MessageParam} (outbound conversion
 *       used by {@code toWire})</li>
 * </ol>
 *
 * <p>Not thread-safe — instances are cheap; create per call site or share a single
 * immutable instance (all state is derived from constructor args).
 */
public final class AnthropicAdapter {

    private final ObjectMapper json;

    public AnthropicAdapter(ObjectMapper json) {
        this.json = json;
    }

    // ────────────────────────────────────────────────────────────
    //  toWire — canonical → Anthropic wire
    // ────────────────────────────────────────────────────────────

    /**
     * Translate an {@link LlmRequest} into an Anthropic {@link CreateMessageRequest}.
     *
     * <p>Rules:
     * <ul>
     *   <li>{@code system} → String if single {@link LlmText} and no cache hints;
     *       otherwise → {@code List<SystemTextBlock>} with {@code cache_control}</li>
     *   <li>Consecutive TOOL messages merge into one {@code role=user} MessageParam
     *       containing N {@link ToolResultBlock}s</li>
     *   <li>ASSISTANT content: {@link LlmText}/{@link LlmToolCall}/{@link LlmThinking}
     *       (only when {@code vendor="anthropic"}) → wire block; {@link LlmOpaque} with
     *       {@code vendor="anthropic"} → {@link UnknownBlock}</li>
     *   <li>{@link LlmMessage#getCacheHints()} attach {@code cache_control} to the
     *       referenced content-block index</li>
     * </ul>
     */
    public CreateMessageRequest toWire(LlmRequest req) {
        CreateMessageRequest.CreateMessageRequestBuilder b = CreateMessageRequest.builder()
                .model(req.getModel())
                .maxTokens(req.getMaxTokens())
                .temperature(req.getTemperature())
                .stopSequences(nullIfEmpty(req.getStopSequences()));

        // system
        b.system(systemToWire(req.getSystem(), req.getSystemCacheHints()));

        // messages: merge consecutive TOOL messages
        b.messages(messagesToWire(req.messagesOrEmpty()));

        // tools
        List<LlmToolDef> tools = req.toolsOrEmpty();
        if (!tools.isEmpty()) {
            List<ToolDef> wireTools = new ArrayList<>(tools.size());
            for (LlmToolDef t : tools) {
                wireTools.add(toolDefToWire(t));
            }
            b.tools(wireTools);
        }

        return b.build();
    }

    /** Anthropic {@code system} field builder. */
    Object systemToWire(List<LlmContent> system, List<CacheHint> hints) {
        if (system == null || system.isEmpty()) return null;

        // Fast path: single LlmText, no cache hints → plain String
        if ((hints == null || hints.isEmpty())
                && system.size() == 1
                && system.get(0) instanceof LlmText t) {
            return t.getText();
        }

        // Full form: List<SystemTextBlock> with optional cache_control
        List<SystemTextBlock> blocks = new ArrayList<>(system.size());
        Map<Integer, CacheHint> hintByIndex = indexHints(hints);
        for (int i = 0; i < system.size(); i++) {
            LlmContent c = system.get(i);
            String text = extractPlainText(c);
            if (text == null) continue;
            SystemTextBlock stb = SystemTextBlock.builder()
                    .type("text")
                    .text(text)
                    .cacheControl(cacheControlFor(hintByIndex.get(i)))
                    .build();
            blocks.add(stb);
        }
        return blocks;
    }

    /**
     * Convert a full canonical message list into wire messages, merging TOOL runs.
     *
     * <p>Public API — used by adapters, SessionStore's Step G1 legacy bridge, and any
     * component that needs to project a canonical history into wire shape.
     */
    public List<MessageParam> messagesToWire(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();

        List<MessageParam> out = new ArrayList<>(messages.size());
        int i = 0;
        while (i < messages.size()) {
            LlmMessage m = messages.get(i);
            if (m.getRole() == LlmRole.TOOL) {
                // Merge consecutive TOOL messages into one role=user with N tool_result blocks
                List<ContentBlock> merged = new ArrayList<>();
                Map<Integer, CacheHint> hintByIdx = new HashMap<>();
                int mergedIdx = 0;
                while (i < messages.size() && messages.get(i).getRole() == LlmRole.TOOL) {
                    LlmMessage tm = messages.get(i);
                    Map<Integer, CacheHint> localHints = indexHints(tm.getCacheHints());
                    for (int k = 0; k < tm.getContent().size(); k++) {
                        LlmContent c = tm.getContent().get(k);
                        ContentBlock wire = toolContentToWire(c);
                        if (wire == null) continue;
                        merged.add(wire);
                        if (localHints.containsKey(k)) {
                            hintByIdx.put(mergedIdx, localHints.get(k));
                        }
                        mergedIdx++;
                    }
                    i++;
                }
                applyCacheHintsToBlocks(merged, hintByIdx);
                out.add(MessageParam.toolResults(cast(merged)));
                continue;
            }
            out.add(messageToWire(m));
            i++;
        }
        return out;
    }

    /** Single non-TOOL message → wire. TOOL messages should go through {@link #messagesToWire}. */
    public MessageParam messageToWire(LlmMessage msg) {
        if (msg.getRole() == LlmRole.TOOL) {
            // TOOL → role=user, tool_result blocks; ignore batching
            List<ContentBlock> blocks = new ArrayList<>();
            for (LlmContent c : msg.getContent()) {
                ContentBlock w = toolContentToWire(c);
                if (w != null) blocks.add(w);
            }
            applyCacheHintsToBlocks(blocks, indexHints(msg.getCacheHints()));
            return MessageParam.toolResults(cast(blocks));
        }
        // USER / ASSISTANT
        List<ContentBlock> blocks = new ArrayList<>();
        for (LlmContent c : msg.getContent()) {
            ContentBlock w = generalContentToWire(c);
            if (w != null) blocks.add(w);
        }
        applyCacheHintsToBlocks(blocks, indexHints(msg.getCacheHints()));
        String role = msg.getRole() == LlmRole.ASSISTANT ? "assistant" : "user";

        // Compact path (fidelity with pre-P2 wire shape): USER with exactly one
        // LlmText and no cache hint → emit String content (Anthropic accepts both).
        // This preserves the JSON shape historical wire messages had, keeping
        // history-diffing / message-boundary invariants stable across the migration.
        if (msg.getRole() == LlmRole.USER
                && (msg.getCacheHints() == null || msg.getCacheHints().isEmpty())
                && blocks.size() == 1
                && blocks.get(0) instanceof TextBlock tb) {
            return MessageParam.user(tb.getText());
        }
        return new MessageParam(role, blocks);
    }

    /**
     * Content valid for ASSISTANT / USER positions.
     * Returns null when the block is dropped (e.g. LlmToolResult in assistant slot).
     */
    private ContentBlock generalContentToWire(LlmContent c) {
        if (c instanceof LlmText t) {
            return new TextBlock(t.getText());
        }
        if (c instanceof LlmToolCall tc) {
            return new ToolUseBlock(tc.getId(), tc.getName(), tc.getInput());
        }
        if (c instanceof LlmThinking th) {
            // Only Anthropic-originating thinking is faithful to Anthropic
            if ("anthropic".equalsIgnoreCase(th.getVendor())) {
                return new ThinkingBlock(th.getText(), th.getSignature());
            }
            return null; // drop other vendors' thinking
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
            return null; // drop other vendors' opaque
        }
        // LlmToolResult in non-TOOL slot — drop (caller shouldn't do this)
        return null;
    }

    /** Content valid for TOOL position (i.e. tool_result blocks only, plus text for background notifications). */
    private ContentBlock toolContentToWire(LlmContent c) {
        if (c instanceof LlmToolResult r) {
            ToolResultBlock trb = new ToolResultBlock(r.getToolCallId(), r.getOutput());
            // Note: current ToolResultBlock DTO doesn't carry is_error; adding as raw would
            // require DTO change. Since jooj currently converts tool errors into text output
            // (see AgentLoopHarness), this is a lossy field for now. Documented in P2 plan.
            return trb;
        }
        if (c instanceof LlmText t) {
            // Text in a TOOL message is used for background-task notifications packed
            // alongside tool_result blocks. See MessageParam.toolResultsWithNotifications.
            return new TextBlock(t.getText());
        }
        return null; // ignore other blocks in TOOL slot
    }

    /**
     * Attach {@code cache_control} to blocks at the specified indices.
     *
     * <p><b>Current limitation</b>: jooj's Anthropic content-block DTOs
     * ({@link TextBlock}, {@link ToolUseBlock}, {@link ToolResultBlock}) do not
     * model a {@code cache_control} field — the existing codebase only puts
     * cache_control on {@link SystemTextBlock}. Message-level cache breakpoints
     * for Anthropic will require extending those DTOs; this method is a hook
     * that no-ops for now so the canonical layer's {@link CacheHint} API is
     * complete-shaped without regressing existing behavior. Follow-up: add the
     * field to the wire DTOs and populate here.
     */
    @SuppressWarnings("unused")
    private void applyCacheHintsToBlocks(List<ContentBlock> blocks, Map<Integer, CacheHint> hintByIdx) {
        // No-op — see javadoc above.
    }

    private ToolDef toolDefToWire(LlmToolDef t) {
        return new ToolDef(t.getName(), t.getDescription(), schemaToInputSchema(t.getSchema()));
    }

    private InputSchema schemaToInputSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return new InputSchema("object", new HashMap<>(), List.of());
        }
        String type = schema.hasNonNull("type") ? schema.get("type").asText() : "object";
        Map<String, Object> properties = new HashMap<>();
        if (schema.hasNonNull("properties")) {
            properties = json.convertValue(schema.get("properties"),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        }
        List<String> required = new ArrayList<>();
        if (schema.hasNonNull("required") && schema.get("required").isArray()) {
            for (JsonNode n : schema.get("required")) {
                required.add(n.asText());
            }
        }
        return new InputSchema(type, properties, required);
    }

    // ────────────────────────────────────────────────────────────
    //  toDomain — Anthropic wire → canonical
    // ────────────────────────────────────────────────────────────

    /** Anthropic response → canonical. */
    public LlmResponse toDomain(CreateMessageResponse wire) {
        List<LlmContent> content = new ArrayList<>();
        if (wire.getContent() != null) {
            for (ContentBlock b : wire.getContent()) {
                LlmContent c = blockToDomain(b);
                if (c != null) content.add(c);
            }
        }
        return LlmResponse.builder()
                .id(wire.getId())
                .model(wire.getModel())
                .content(content)
                .stopReason(stopReasonToDomain(wire.getStopReason()))
                .stopSequence(wire.getStopSequence())
                .usage(usageToDomain(wire.getUsage()))
                .build();
    }

    /** Legacy MessageParam → canonical LlmMessage. Used by Step B transitional callers. */
    public LlmMessage messageToDomain(MessageParam wire) {
        String role = wire.getRole();
        Object rawContent = wire.getContent();

        // Normalize content into List<ContentBlock>
        List<ContentBlock> wireBlocks = new ArrayList<>();
        if (rawContent instanceof String s) {
            wireBlocks.add(new TextBlock(s));
        } else if (rawContent instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof ContentBlock cb) wireBlocks.add(cb);
            }
        }

        // Detect TOOL semantics: role=user with tool_result blocks → LlmRole.TOOL
        boolean hasToolResult = wireBlocks.stream().anyMatch(b -> b instanceof ToolResultBlock);
        boolean allToolResultOrText = !wireBlocks.isEmpty()
                && wireBlocks.stream().allMatch(b -> b instanceof ToolResultBlock || b instanceof TextBlock);

        LlmRole llmRole;
        if ("assistant".equalsIgnoreCase(role)) {
            llmRole = LlmRole.ASSISTANT;
        } else if (hasToolResult && allToolResultOrText) {
            llmRole = LlmRole.TOOL;
        } else {
            llmRole = LlmRole.USER;
        }

        List<LlmContent> canonical = new ArrayList<>(wireBlocks.size());
        for (ContentBlock b : wireBlocks) {
            LlmContent c = blockToDomain(b);
            if (c != null) canonical.add(c);
        }
        return new LlmMessage(llmRole, canonical);
    }

    /** Single wire block → canonical block. */
    LlmContent blockToDomain(ContentBlock b) {
        if (b instanceof TextBlock t) {
            return new LlmText(t.getText());
        }
        if (b instanceof ToolUseBlock tu) {
            return new LlmToolCall(tu.getId(), tu.getName(), tu.getInput());
        }
        if (b instanceof ToolResultBlock tr) {
            Object c = tr.getContent();
            String output = c instanceof String s ? s : (c == null ? "" : c.toString());
            return new LlmToolResult(tr.getToolUseId(), output, false);
        }
        if (b instanceof ThinkingBlock th) {
            return new LlmThinking(th.getThinking(), th.getSignature(), "anthropic");
        }
        if (b instanceof UnknownBlock ub) {
            Map<String, Object> raw = new HashMap<>(ub.getProperties());
            String type = raw.get("type") != null ? raw.get("type").toString() : "unknown";
            raw.remove("type");
            return new LlmOpaque("anthropic", type, raw);
        }
        return null;
    }

    private LlmStopReason stopReasonToDomain(String wire) {
        if (wire == null) return LlmStopReason.UNKNOWN;
        StopReason sr = StopReason.from(wire);
        return switch (sr) {
            case END_TURN -> LlmStopReason.END_TURN;
            case TOOL_USE -> LlmStopReason.TOOL_CALLS;
            case MAX_TOKENS -> LlmStopReason.MAX_TOKENS;
            case STOP_SEQUENCE -> LlmStopReason.STOP_SEQUENCE;
            case UNKNOWN -> LlmStopReason.UNKNOWN;
        };
    }

    private LlmUsage usageToDomain(Usage wire) {
        if (wire == null) return null;
        return LlmUsage.builder()
                .inputTokens(wire.getInputTokens())
                .outputTokens(wire.getOutputTokens())
                .cacheCreationInputTokens(wire.getCacheCreationInputTokens())
                .cacheReadInputTokens(wire.getCacheReadInputTokens())
                .reasoningTokens(null)
                .build();
    }

    // ────────────────────────────────────────────────────────────
    //  Error classification
    // ────────────────────────────────────────────────────────────

    /**
     * Classify an Anthropic-side failure into a canonical {@link LlmException}.
     *
     * <p>Rules per P2 plan §二 "错误分类":
     * <ul>
     *   <li>400 body contains "prompt_too_long"/"prompt is too long" → PROMPT_TOO_LONG</li>
     *   <li>401 → AUTH</li>
     *   <li>429 → RATE_LIMITED</li>
     *   <li>529 or 5xx → OVERLOADED</li>
     *   <li>0 (IO error) → IO_ERROR</li>
     *   <li>other 4xx → BAD_REQUEST</li>
     *   <li>else → UNKNOWN</li>
     * </ul>
     */
    public LlmException classify(AnthropicException e) {
        int status = e.getStatusCode();
        String body = e.getResponseBody() != null ? e.getResponseBody() : "";
        String lower = body.toLowerCase(Locale.ROOT);

        LlmErrorKind kind;
        if (status == 0) {
            kind = LlmErrorKind.IO_ERROR;
        } else if (status == 400 && (lower.contains("prompt is too long") || lower.contains("prompt_too_long"))) {
            kind = LlmErrorKind.PROMPT_TOO_LONG;
        } else if (status == 401) {
            kind = LlmErrorKind.AUTH;
        } else if (status == 429) {
            kind = LlmErrorKind.RATE_LIMITED;
        } else if (status == 529 || (status >= 500 && status < 600)) {
            kind = LlmErrorKind.OVERLOADED;
        } else if (status >= 400 && status < 500) {
            kind = LlmErrorKind.BAD_REQUEST;
        } else {
            kind = LlmErrorKind.UNKNOWN;
        }
        return new LlmException(kind, status, e.getMessage(), e);
    }

    // ────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────

    private static <T> List<T> nullIfEmpty(List<T> list) {
        return (list == null || list.isEmpty()) ? null : list;
    }

    /** Extract text from an LlmText / LlmOpaque(vendor=anthropic,type=text) — null if not textual. */
    private static String extractPlainText(LlmContent c) {
        if (c instanceof LlmText t) return t.getText();
        return null;
    }

    private static Map<Integer, CacheHint> indexHints(List<CacheHint> hints) {
        if (hints == null || hints.isEmpty()) return Map.of();
        Map<Integer, CacheHint> map = new HashMap<>();
        for (CacheHint h : hints) {
            map.put(h.getIndex(), h);
        }
        return map;
    }

    private static CacheControl cacheControlFor(CacheHint h) {
        if (h == null) return null;
        return h.getTier() == CacheTier.EPHEMERAL_1H
                ? CacheControl.ephemeral1h()
                : CacheControl.ephemeral();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<ToolResultBlock> cast(List<ContentBlock> list) {
        // Callers of MessageParam.toolResults pass ToolResultBlock; we may have mixed
        // TextBlock (task notifications). MessageParam.toolResultsWithNotifications is the
        // right factory when notifications are present, but at Step A the merged list is
        // the flat list of blocks — we just want to reuse the "role=user" constructor.
        return (List) list;
    }
}
