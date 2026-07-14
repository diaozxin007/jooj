package com.xilidou.jooj.llm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmErrorKind;
import com.xilidou.jooj.llm.domain.LlmException;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;
import com.xilidou.jooj.llm.domain.LlmStopReason;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolDef;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip tests for {@link OpenAiChatAdapter}. Covers:
 * <ul>
 *   <li>plain gpt-4o request (system message + user turn)</li>
 *   <li>reasoning-model (o1) folding: no system, system text into first user, no temperature</li>
 *   <li>tool_calls outbound (assistant with LlmToolCall → tool_calls array)</li>
 *   <li>tool result outbound (TOOL role → role=tool messages)</li>
 *   <li>response parsing: tool_calls + finish_reason mapping</li>
 *   <li>usage: reasoning_tokens and cached_tokens</li>
 *   <li>error classification: context_length_exceeded, insufficient_quota, 429, 5xx</li>
 * </ul>
 */
class OpenAiChatAdapterTest {

    private final ObjectMapper json = JsonMappers.newMapper();
    private final OpenAiChatAdapter adapter = new OpenAiChatAdapter(json);

    // ────────────────────────────────────────────────────────────
    //  toWire — chat models
    // ────────────────────────────────────────────────────────────

    @Test
    void plainChat_systemAndUserMessages() {
        LlmRequest req = LlmRequest.builder()
                .model("gpt-4o-mini")
                .maxTokens(512)
                .temperature(0.7)
                .system(List.of(new LlmText("You are helpful")))
                .messages(List.of(LlmMessage.userText("hello")))
                .build();

        JsonNode wire = adapter.toWire(req);

        assertThat(wire.get("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(wire.get("max_tokens").asInt()).isEqualTo(512);
        assertThat(wire.has("max_completion_tokens")).isFalse();
        assertThat(wire.get("temperature").asDouble()).isEqualTo(0.7);
        JsonNode messages = wire.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("You are helpful");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("content").asText()).isEqualTo("hello");
    }

    @Test
    void reasoningModel_o1_foldsSystemIntoFirstUserAndUsesMaxCompletionTokens() {
        LlmRequest req = LlmRequest.builder()
                .model("o1-mini")
                .maxTokens(2048)
                .temperature(0.5)
                .system(List.of(new LlmText("Reason carefully")))
                .messages(List.of(LlmMessage.userText("what is 2+2?")))
                .build();

        JsonNode wire = adapter.toWire(req);

        assertThat(wire.has("max_tokens")).isFalse();
        assertThat(wire.get("max_completion_tokens").asInt()).isEqualTo(2048);
        assertThat(wire.has("temperature")).isFalse();
        JsonNode messages = wire.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).get("content").asText())
                .startsWith("Reason carefully")
                .contains("what is 2+2?");
    }

    @Test
    void reasoningModel_o3_prefixDetected() {
        assertThat(OpenAiChatAdapter.isReasoningModel("o3-mini")).isTrue();
        assertThat(OpenAiChatAdapter.isReasoningModel("o4-preview")).isTrue();
        assertThat(OpenAiChatAdapter.isReasoningModel("gpt-4o")).isFalse();
    }

    @Test
    void assistantWithToolCalls_emitsToolCallsArray() {
        ObjectNode input = json.createObjectNode().put("command", "ls");
        LlmMessage assistant = LlmMessage.assistant(List.of(
                new LlmText("I'll list files"),
                new LlmToolCall("call_1", "bash", input)
        ));

        LlmRequest req = LlmRequest.builder()
                .model("gpt-4o")
                .messages(List.of(LlmMessage.userText("ls please"), assistant))
                .build();

        JsonNode wire = adapter.toWire(req);
        JsonNode messages = wire.get("messages");
        JsonNode am = messages.get(1);
        assertThat(am.get("role").asText()).isEqualTo("assistant");
        assertThat(am.get("content").asText()).isEqualTo("I'll list files");
        assertThat(am.get("tool_calls")).hasSize(1);
        JsonNode tc = am.get("tool_calls").get(0);
        assertThat(tc.get("id").asText()).isEqualTo("call_1");
        assertThat(tc.get("type").asText()).isEqualTo("function");
        assertThat(tc.get("function").get("name").asText()).isEqualTo("bash");
        // arguments is a stringified JSON (per OpenAI wire spec)
        String args = tc.get("function").get("arguments").asText();
        assertThat(args).contains("\"command\"").contains("\"ls\"");
    }

    @Test
    void toolResults_emitEachAsRoleToolMessage() {
        LlmMessage tool = LlmMessage.toolResults(List.of(
                LlmToolResult.success("call_1", "output1"),
                LlmToolResult.success("call_2", "output2")
        ));

        LlmRequest req = LlmRequest.builder()
                .model("gpt-4o")
                .messages(List.of(tool))
                .build();

        JsonNode wire = adapter.toWire(req);
        JsonNode messages = wire.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("tool");
        assertThat(messages.get(0).get("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("output1");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("tool");
        assertThat(messages.get(1).get("tool_call_id").asText()).isEqualTo("call_2");
    }

    @Test
    void tools_emitFunctionShell() {
        ObjectNode schema = json.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("command").put("type", "string");
        LlmToolDef def = new LlmToolDef("bash", "Run shell", schema);

        LlmRequest req = LlmRequest.builder()
                .model("gpt-4o")
                .tools(List.of(def))
                .build();

        JsonNode wire = adapter.toWire(req);
        JsonNode tools = wire.get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("type").asText()).isEqualTo("function");
        assertThat(tools.get(0).get("function").get("name").asText()).isEqualTo("bash");
        assertThat(tools.get(0).get("function").get("parameters").get("type").asText())
                .isEqualTo("object");
    }

    @Test
    void stopSequences_emitAsStopArray() {
        LlmRequest req = LlmRequest.builder()
                .model("gpt-4o")
                .stopSequences(List.of("<|end|>", "STOP"))
                .messages(List.of(LlmMessage.userText("hi")))
                .build();
        JsonNode wire = adapter.toWire(req);
        JsonNode stop = wire.get("stop");
        assertThat(stop).hasSize(2);
        assertThat(stop.get(0).asText()).isEqualTo("<|end|>");
    }

    // ────────────────────────────────────────────────────────────
    //  toDomain — response parsing
    // ────────────────────────────────────────────────────────────

    @Test
    void response_endTurn_textContent() throws Exception {
        String body = """
                {
                  "id": "chatcmpl_1",
                  "model": "gpt-4o-2024-08-06",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "hello there"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 12, "completion_tokens": 6}
                }
                """;
        LlmResponse res = adapter.toDomain(json.readTree(body));
        assertThat(res.getId()).isEqualTo("chatcmpl_1");
        assertThat(res.getModel()).isEqualTo("gpt-4o-2024-08-06");
        assertThat(res.getStopReason()).isEqualTo(LlmStopReason.END_TURN);
        assertThat(res.firstText()).isEqualTo("hello there");
        assertThat(res.getUsage().getInputTokens()).isEqualTo(12);
        assertThat(res.getUsage().getOutputTokens()).isEqualTo(6);
    }

    @Test
    void response_toolCalls_parseArgumentsFromStringifiedJson() throws Exception {
        String body = """
                {
                  "id": "chatcmpl_2",
                  "model": "gpt-4o",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call_abc",
                        "type": "function",
                        "function": {"name": "bash", "arguments": "{\\"command\\":\\"ls -la\\"}"}
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {"prompt_tokens": 50, "completion_tokens": 20}
                }
                """;
        LlmResponse res = adapter.toDomain(json.readTree(body));
        assertThat(res.getStopReason()).isEqualTo(LlmStopReason.TOOL_CALLS);
        assertThat(res.needsToolExecution()).isTrue();
        assertThat(res.toolCalls()).hasSize(1);
        LlmToolCall call = res.toolCalls().get(0);
        assertThat(call.getId()).isEqualTo("call_abc");
        assertThat(call.getName()).isEqualTo("bash");
        assertThat(call.getInput().get("command").asText()).isEqualTo("ls -la");
    }

    @Test
    void response_contentFilter_mapsToRefusal() throws Exception {
        String body = """
                {"id":"1","model":"gpt-4o",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"..."},
                             "finish_reason":"content_filter"}]}
                """;
        LlmResponse res = adapter.toDomain(json.readTree(body));
        assertThat(res.getStopReason()).isEqualTo(LlmStopReason.REFUSAL);
    }

    @Test
    void response_length_mapsToMaxTokens() throws Exception {
        String body = """
                {"id":"1","model":"gpt-4o",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"..."},
                             "finish_reason":"length"}]}
                """;
        LlmResponse res = adapter.toDomain(json.readTree(body));
        assertThat(res.getStopReason()).isEqualTo(LlmStopReason.MAX_TOKENS);
    }

    @Test
    void response_usage_reasoningTokens_o1() throws Exception {
        String body = """
                {
                  "id":"1","model":"o1-preview",
                  "choices":[{"index":0,"message":{"role":"assistant","content":"..."},
                              "finish_reason":"stop"}],
                  "usage":{
                    "prompt_tokens":100,"completion_tokens":50,
                    "prompt_tokens_details":{"cached_tokens":80},
                    "completion_tokens_details":{"reasoning_tokens":40}
                  }
                }
                """;
        LlmResponse res = adapter.toDomain(json.readTree(body));
        assertThat(res.getUsage().getCacheReadInputTokens()).isEqualTo(80);
        assertThat(res.getUsage().getReasoningTokens()).isEqualTo(40);
    }

    // ────────────────────────────────────────────────────────────
    //  Error classification
    // ────────────────────────────────────────────────────────────

    @Test
    void classify_contextLengthExceeded() {
        String body = "{\"error\":{\"code\":\"context_length_exceeded\","
                + "\"message\":\"This model's maximum context length is 128000 tokens\"}}";
        LlmException e = adapter.classify(400, body, null);
        assertThat(e.getKind()).isEqualTo(LlmErrorKind.PROMPT_TOO_LONG);
    }

    @Test
    void classify_insufficientQuota_isBadRequestNotRateLimit() {
        String body = "{\"error\":{\"type\":\"insufficient_quota\",\"message\":\"...\"}}";
        LlmException e = adapter.classify(429, body, null);
        assertThat(e.getKind()).isEqualTo(LlmErrorKind.BAD_REQUEST);
    }

    @Test
    void classify_plain429_rateLimited() {
        String body = "{\"error\":{\"type\":\"rate_limit_exceeded\"}}";
        LlmException e = adapter.classify(429, body, null);
        assertThat(e.getKind()).isEqualTo(LlmErrorKind.RATE_LIMITED);
    }

    @Test
    void classify_5xx_overloaded() {
        assertThat(adapter.classify(500, "", null).getKind()).isEqualTo(LlmErrorKind.OVERLOADED);
        assertThat(adapter.classify(503, "", null).getKind()).isEqualTo(LlmErrorKind.OVERLOADED);
    }

    @Test
    void classify_ioError_status0() {
        LlmException e = adapter.classify(0, "connection reset", null);
        assertThat(e.getKind()).isEqualTo(LlmErrorKind.IO_ERROR);
        assertThat(e.isRetryable()).isTrue();
    }

    @Test
    void classify_auth_401() {
        LlmException e = adapter.classify(401, "invalid api key", null);
        assertThat(e.getKind()).isEqualTo(LlmErrorKind.AUTH);
    }
}
