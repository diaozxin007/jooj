package com.xilidou.jooj.llm.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.http.AnthropicException;
import com.xilidou.jooj.http.dto.ContentBlock;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.SystemTextBlock;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ThinkingBlock;
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
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;
import com.xilidou.jooj.llm.domain.LlmRole;
import com.xilidou.jooj.llm.domain.LlmStopReason;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmThinking;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolDef;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip tests for {@link AnthropicAdapter}, covering the 5 fixture forms
 * required by P2 plan §五 · Step A:
 * <ol>
 *   <li>text-only conversation (user → assistant text)</li>
 *   <li>tool_use (assistant asks for a tool)</li>
 *   <li>tool_result (TOOL role → wire {@code role=user} with tool_result blocks)</li>
 *   <li>thinking block (Anthropic extended thinking preserved)</li>
 *   <li>cache_control (system-level cache breakpoint via CacheHint)</li>
 * </ol>
 */
class AnthropicAdapterTest {

    private final ObjectMapper json = JsonMappers.newMapper();
    private final AnthropicAdapter adapter = new AnthropicAdapter(json);

    // ────────────────────────────────────────────────────────────
    //  1. text-only round-trip
    // ────────────────────────────────────────────────────────────

    @Test
    void textOnly_toWire_producesStringSystemAndUserMessage() {
        LlmRequest req = LlmRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(1024)
                .system(List.of(new LlmText("You are helpful")))
                .messages(List.of(LlmMessage.userText("hi")))
                .build();

        CreateMessageRequest wire = adapter.toWire(req);

        assertThat(wire.getModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(wire.getMaxTokens()).isEqualTo(1024);
        assertThat(wire.getSystem()).isInstanceOf(String.class);
        assertThat((String) wire.getSystem()).isEqualTo("You are helpful");
        assertThat(wire.getMessages()).hasSize(1);
        MessageParam m = wire.getMessages().get(0);
        assertThat(m.getRole()).isEqualTo("user");
        // Compact path: single LlmText USER message → String content (preserves
        // pre-P2 wire shape; Anthropic accepts both String and List<ContentBlock>).
        assertThat(m.getContent()).isInstanceOf(String.class);
        assertThat((String) m.getContent()).isEqualTo("hi");
    }

    @Test
    void textOnly_response_toDomain_endTurn() {
        CreateMessageResponse wire = new CreateMessageResponse(
                "msg_1", "message", "assistant",
                List.of(new TextBlock("hello")),
                "claude-sonnet-4-6",
                "end_turn",
                null,
                new Usage(10, 5, null, null));

        LlmResponse res = adapter.toDomain(wire);

        assertThat(res.getId()).isEqualTo("msg_1");
        assertThat(res.getStopReason()).isEqualTo(LlmStopReason.END_TURN);
        assertThat(res.needsToolExecution()).isFalse();
        assertThat(res.firstText()).isEqualTo("hello");
        assertThat(res.getUsage().getInputTokens()).isEqualTo(10);
        assertThat(res.getUsage().getOutputTokens()).isEqualTo(5);
    }

    // ────────────────────────────────────────────────────────────
    //  2. tool_use round-trip
    // ────────────────────────────────────────────────────────────

    @Test
    void toolUse_toDomain_populatesToolCalls() {
        ObjectNode input = json.createObjectNode();
        input.put("command", "ls -la");

        CreateMessageResponse wire = new CreateMessageResponse(
                "msg_2", "message", "assistant",
                List.of(new TextBlock("I'll list files"), new ToolUseBlock("toolu_1", "bash", input)),
                "claude-sonnet-4-6",
                "tool_use",
                null,
                new Usage(50, 30, null, null));

        LlmResponse res = adapter.toDomain(wire);

        assertThat(res.getStopReason()).isEqualTo(LlmStopReason.TOOL_CALLS);
        assertThat(res.needsToolExecution()).isTrue();
        assertThat(res.getContent()).hasSize(2);
        assertThat(res.toolCalls()).hasSize(1);
        LlmToolCall call = res.toolCalls().get(0);
        assertThat(call.getId()).isEqualTo("toolu_1");
        assertThat(call.getName()).isEqualTo("bash");
        assertThat(call.getInput().get("command").asText()).isEqualTo("ls -la");
    }

    @Test
    void toolUse_toWire_producesAssistantMessageWithBothBlocks() {
        ObjectNode input = json.createObjectNode();
        input.put("path", "/tmp");

        LlmMessage assistantMsg = LlmMessage.assistant(List.of(
                new LlmText("Let me check"),
                new LlmToolCall("toolu_2", "ls", input)
        ));

        LlmRequest req = LlmRequest.builder()
                .model("claude-sonnet-4-6")
                .messages(List.of(LlmMessage.userText("look at /tmp"), assistantMsg))
                .build();

        CreateMessageRequest wire = adapter.toWire(req);
        MessageParam am = wire.getMessages().get(1);
        assertThat(am.getRole()).isEqualTo("assistant");
        @SuppressWarnings("unchecked")
        List<ContentBlock> blocks = (List<ContentBlock>) am.getContent();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).isInstanceOf(TextBlock.class);
        assertThat(blocks.get(1)).isInstanceOf(ToolUseBlock.class);
        assertThat(((ToolUseBlock) blocks.get(1)).getName()).isEqualTo("ls");
    }

    // ────────────────────────────────────────────────────────────
    //  3. tool_result round-trip (canonical TOOL role → wire role=user)
    // ────────────────────────────────────────────────────────────

    @Test
    void toolResult_toWire_singleToolMessageBecomesUserMessage() {
        LlmMessage tool = LlmMessage.toolResults(List.of(
                LlmToolResult.success("toolu_1", "file1.txt\nfile2.txt")
        ));

        LlmRequest req = LlmRequest.builder()
                .model("claude-sonnet-4-6")
                .messages(List.of(tool))
                .build();

        CreateMessageRequest wire = adapter.toWire(req);
        assertThat(wire.getMessages()).hasSize(1);
        MessageParam m = wire.getMessages().get(0);
        // TOOL → role=user with tool_result blocks
        assertThat(m.getRole()).isEqualTo("user");
        @SuppressWarnings("unchecked")
        List<ContentBlock> blocks = (List<ContentBlock>) m.getContent();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(ToolResultBlock.class);
        ToolResultBlock trb = (ToolResultBlock) blocks.get(0);
        assertThat(trb.getToolUseId()).isEqualTo("toolu_1");
        assertThat(trb.getContent()).isEqualTo("file1.txt\nfile2.txt");
    }

    @Test
    void toolResult_consecutiveToolMessages_mergeIntoOneWireMessage() {
        LlmMessage t1 = LlmMessage.toolResults(List.of(LlmToolResult.success("toolu_1", "a")));
        LlmMessage t2 = LlmMessage.toolResults(List.of(LlmToolResult.success("toolu_2", "b")));

        LlmRequest req = LlmRequest.builder()
                .model("claude-sonnet-4-6")
                .messages(List.of(t1, t2))
                .build();

        CreateMessageRequest wire = adapter.toWire(req);
        // The two TOOL messages merge into ONE role=user wire message with 2 tool_result blocks
        assertThat(wire.getMessages()).hasSize(1);
        @SuppressWarnings("unchecked")
        List<ContentBlock> blocks = (List<ContentBlock>) wire.getMessages().get(0).getContent();
        assertThat(blocks).hasSize(2);
        assertThat(((ToolResultBlock) blocks.get(0)).getToolUseId()).isEqualTo("toolu_1");
        assertThat(((ToolResultBlock) blocks.get(1)).getToolUseId()).isEqualTo("toolu_2");
    }

    @Test
    void toolResult_wireToDomain_detectsToolRoleFromContent() {
        MessageParam wire = MessageParam.toolResults(List.of(
                new ToolResultBlock("toolu_1", "output")
        ));
        LlmMessage domain = adapter.messageToDomain(wire);
        assertThat(domain.getRole()).isEqualTo(LlmRole.TOOL);
        assertThat(domain.getContent()).hasSize(1);
        assertThat(domain.getContent().get(0)).isInstanceOf(LlmToolResult.class);
        assertThat(((LlmToolResult) domain.getContent().get(0)).getToolCallId()).isEqualTo("toolu_1");
    }

    // ────────────────────────────────────────────────────────────
    //  4. thinking round-trip
    // ────────────────────────────────────────────────────────────

    @Test
    void thinking_toDomain_carriesVendorAnthropic() {
        CreateMessageResponse wire = new CreateMessageResponse(
                "msg_3", "message", "assistant",
                List.of(new ThinkingBlock("Let me consider...", "sig_xyz"),
                        new TextBlock("Answer: 42")),
                "claude-opus-4-6",
                "end_turn",
                null,
                new Usage(20, 10, null, null));

        LlmResponse res = adapter.toDomain(wire);
        assertThat(res.getContent()).hasSize(2);
        assertThat(res.getContent().get(0)).isInstanceOf(LlmThinking.class);
        LlmThinking th = (LlmThinking) res.getContent().get(0);
        assertThat(th.getText()).isEqualTo("Let me consider...");
        assertThat(th.getSignature()).isEqualTo("sig_xyz");
        assertThat(th.getVendor()).isEqualTo("anthropic");
    }

    @Test
    void thinking_toWire_roundTripsWhenVendorAnthropic() {
        LlmMessage msg = LlmMessage.assistant(List.of(
                new LlmThinking("thought", "sig_1", "anthropic"),
                new LlmText("answer")
        ));

        LlmRequest req = LlmRequest.builder()
                .model("claude-sonnet-4-6")
                .messages(List.of(msg))
                .build();

        CreateMessageRequest wire = adapter.toWire(req);
        @SuppressWarnings("unchecked")
        List<ContentBlock> blocks = (List<ContentBlock>) wire.getMessages().get(0).getContent();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).isInstanceOf(ThinkingBlock.class);
        ThinkingBlock tb = (ThinkingBlock) blocks.get(0);
        assertThat(tb.getThinking()).isEqualTo("thought");
        assertThat(tb.getSignature()).isEqualTo("sig_1");
    }

    @Test
    void thinking_toWire_dropsForeignVendor() {
        LlmMessage msg = LlmMessage.assistant(List.of(
                new LlmThinking("thought", null, "openai"),
                new LlmText("answer")
        ));
        LlmRequest req = LlmRequest.builder()
                .model("claude-sonnet-4-6")
                .messages(List.of(msg))
                .build();
        CreateMessageRequest wire = adapter.toWire(req);
        @SuppressWarnings("unchecked")
        List<ContentBlock> blocks = (List<ContentBlock>) wire.getMessages().get(0).getContent();
        // The non-anthropic thinking block is dropped
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(TextBlock.class);
    }

    // ────────────────────────────────────────────────────────────
    //  5. cache_control round-trip (system-level)
    // ────────────────────────────────────────────────────────────

    @Test
    void cacheHint_onSystem_emitsSystemBlocksWithCacheControl() {
        LlmRequest req = LlmRequest.builder()
                .model("claude-sonnet-4-6")
                .system(List.of(new LlmText("stable identity + tools"),
                        new LlmText("dynamic memory")))
                .systemCacheHints(List.of(new CacheHint(0, CacheTier.EPHEMERAL_5M)))
                .build();

        CreateMessageRequest wire = adapter.toWire(req);
        assertThat(wire.getSystem()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<SystemTextBlock> blocks = (List<SystemTextBlock>) wire.getSystem();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).getCacheControl()).isNotNull();
        assertThat(blocks.get(0).getCacheControl().getType()).isEqualTo("ephemeral");
        assertThat(blocks.get(0).getCacheControl().getTtl()).isNull(); // 5m default
        assertThat(blocks.get(1).getCacheControl()).isNull();
    }

    @Test
    void cacheHint_onSystem_1h_emitsTtlField() {
        LlmRequest req = LlmRequest.builder()
                .model("claude-sonnet-4-6")
                .system(List.of(new LlmText("stable")))
                .systemCacheHints(List.of(new CacheHint(0, CacheTier.EPHEMERAL_1H)))
                .build();
        CreateMessageRequest wire = adapter.toWire(req);
        @SuppressWarnings("unchecked")
        List<SystemTextBlock> blocks = (List<SystemTextBlock>) wire.getSystem();
        assertThat(blocks.get(0).getCacheControl().getTtl()).isEqualTo("1h");
    }

    @Test
    void systemSingleTextNoHints_serializesAsPlainString() {
        LlmRequest req = LlmRequest.builder()
                .model("claude-sonnet-4-6")
                .system(List.of(new LlmText("just a string")))
                .build();
        CreateMessageRequest wire = adapter.toWire(req);
        assertThat(wire.getSystem()).isInstanceOf(String.class);
        assertThat(wire.getSystem()).isEqualTo("just a string");
    }

    // ────────────────────────────────────────────────────────────
    //  Unknown block forward-compat
    // ────────────────────────────────────────────────────────────

    @Test
    void unknownBlock_toDomain_preservedAsLlmOpaque() {
        UnknownBlock ub = new UnknownBlock();
        ub.setProperty("type", "image");
        ub.setProperty("source", "https://example.com/img.png");

        CreateMessageResponse wire = new CreateMessageResponse(
                "msg_x", "message", "assistant",
                List.of(ub),
                "claude-sonnet-4-6",
                "end_turn",
                null,
                new Usage(1, 1, null, null));

        LlmResponse res = adapter.toDomain(wire);
        assertThat(res.getContent()).hasSize(1);
        assertThat(res.getContent().get(0)).isInstanceOf(
                com.xilidou.jooj.llm.domain.LlmOpaque.class);
        var op = (com.xilidou.jooj.llm.domain.LlmOpaque) res.getContent().get(0);
        assertThat(op.getVendor()).isEqualTo("anthropic");
        assertThat(op.getType()).isEqualTo("image");
        assertThat(op.getRaw().get("source")).isEqualTo("https://example.com/img.png");
    }

    // ────────────────────────────────────────────────────────────
    //  Stop reason & usage mapping
    // ────────────────────────────────────────────────────────────

    @Test
    void stopReason_maxTokens_mapsCorrectly() {
        CreateMessageResponse wire = new CreateMessageResponse(
                "msg_4", "message", "assistant", List.of(new TextBlock("cut...")),
                "claude-sonnet-4-6", "max_tokens", null, new Usage(100, 8000, null, null));
        LlmResponse res = adapter.toDomain(wire);
        assertThat(res.getStopReason()).isEqualTo(LlmStopReason.MAX_TOKENS);
    }

    @Test
    void stopReason_stopSequence_mapsCorrectly() {
        CreateMessageResponse wire = new CreateMessageResponse(
                "msg_5", "message", "assistant", List.of(new TextBlock("stopped")),
                "claude-sonnet-4-6", "stop_sequence", "STOP", new Usage(10, 5, null, null));
        LlmResponse res = adapter.toDomain(wire);
        assertThat(res.getStopReason()).isEqualTo(LlmStopReason.STOP_SEQUENCE);
        assertThat(res.getStopSequence()).isEqualTo("STOP");
    }

    @Test
    void usage_cacheFields_roundTrip() {
        CreateMessageResponse wire = new CreateMessageResponse(
                "msg_6", "message", "assistant", List.of(new TextBlock("ok")),
                "claude-sonnet-4-6", "end_turn", null,
                new Usage(1000, 50, 800, 200));
        LlmResponse res = adapter.toDomain(wire);
        assertThat(res.getUsage().getCacheCreationInputTokens()).isEqualTo(800);
        assertThat(res.getUsage().getCacheReadInputTokens()).isEqualTo(200);
        assertThat(res.getUsage().getReasoningTokens()).isNull();
    }

    // ────────────────────────────────────────────────────────────
    //  Error classification
    // ────────────────────────────────────────────────────────────

    @Test
    void classify_promptTooLong() {
        AnthropicException e = new AnthropicException(400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"prompt is too long: 250000 tokens > 200000 maximum\"}}");
        LlmException le = adapter.classify(e);
        assertThat(le.getKind()).isEqualTo(LlmErrorKind.PROMPT_TOO_LONG);
        assertThat(le.isPromptTooLong()).isTrue();
    }

    @Test
    void classify_overloaded_529() {
        AnthropicException e = new AnthropicException(529, "{\"error\":\"overloaded\"}");
        LlmException le = adapter.classify(e);
        assertThat(le.getKind()).isEqualTo(LlmErrorKind.OVERLOADED);
        assertThat(le.isRetryable()).isTrue();
    }

    @Test
    void classify_rateLimited() {
        AnthropicException e = new AnthropicException(429, "{\"error\":\"rate limited\"}");
        assertThat(adapter.classify(e).getKind()).isEqualTo(LlmErrorKind.RATE_LIMITED);
    }

    @Test
    void classify_auth() {
        AnthropicException e = new AnthropicException(401, "invalid key");
        assertThat(adapter.classify(e).getKind()).isEqualTo(LlmErrorKind.AUTH);
    }

    @Test
    void classify_ioError() {
        AnthropicException e = new AnthropicException(0, "connection reset");
        assertThat(adapter.classify(e).getKind()).isEqualTo(LlmErrorKind.IO_ERROR);
    }

    // ────────────────────────────────────────────────────────────
    //  Tool definition mapping
    // ────────────────────────────────────────────────────────────

    @Test
    void toolDef_toWire_populatesInputSchema() {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("command").put("type", "string");
        schema.putArray("required").add("command");

        LlmToolDef def = new LlmToolDef("bash", "Run a shell command.", schema);
        LlmRequest req = LlmRequest.builder()
                .model("claude-sonnet-4-6")
                .tools(List.of(def))
                .build();

        CreateMessageRequest wire = adapter.toWire(req);
        assertThat(wire.getTools()).hasSize(1);
        assertThat(wire.getTools().get(0).getName()).isEqualTo("bash");
        assertThat(wire.getTools().get(0).getDescription()).isEqualTo("Run a shell command.");
        assertThat(wire.getTools().get(0).getInputSchema().getType()).isEqualTo("object");
        assertThat(wire.getTools().get(0).getInputSchema().getRequired()).containsExactly("command");
    }

    // ────────────────────────────────────────────────────────────
    //  messageToDomain: legacy shape ingestion
    // ────────────────────────────────────────────────────────────

    @Test
    void messageToDomain_stringContent_becomesUserWithText() {
        MessageParam wire = MessageParam.user("plain string");
        LlmMessage m = adapter.messageToDomain(wire);
        assertThat(m.getRole()).isEqualTo(LlmRole.USER);
        assertThat(m.getContent()).hasSize(1);
        assertThat(m.getContent().get(0)).isInstanceOf(LlmText.class);
        assertThat(((LlmText) m.getContent().get(0)).getText()).isEqualTo("plain string");
    }

    @Test
    void messageToDomain_assistantWithBlocks_mapsAll() {
        ObjectNode input = json.createObjectNode();
        input.put("cmd", "ls");
        MessageParam wire = MessageParam.assistant(List.of(
                new TextBlock("running"),
                new ToolUseBlock("toolu_1", "bash", input)
        ));
        LlmMessage m = adapter.messageToDomain(wire);
        assertThat(m.getRole()).isEqualTo(LlmRole.ASSISTANT);
        assertThat(m.getContent()).hasSize(2);
        assertThat(m.getContent().get(1)).isInstanceOf(LlmToolCall.class);
    }
}
