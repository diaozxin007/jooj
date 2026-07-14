package com.xilidou.jooj.http;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.Usage;
import com.xilidou.jooj.llm.LlmClient;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;
import com.xilidou.jooj.llm.domain.LlmStopReason;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step B — regression test that {@link ModelRouter} correctly implements the P2
 * canonical {@link LlmClient} contract and routes canonical requests through the
 * same prefix-matching logic as legacy Anthropic-shaped requests.
 *
 * <p>Uses a lightweight {@link StubProvider} that captures whichever entrypoint
 * was called and returns a canonical response; verifies routing selects the right
 * provider by model prefix and that responses round-trip through the canonical
 * shape.
 */
class ModelRouterCanonicalTest {

    private final com.fasterxml.jackson.databind.ObjectMapper json = JsonMappers.newMapper();

    @Test
    void canonicalRequest_routesByPrefix() {
        StubProvider anthropic = new StubProvider("anthropic", List.of("claude-"), "hello from anthropic");
        StubProvider openai = new StubProvider("openai", List.of("gpt-", "o1-", "o3-"), "hello from openai");

        ModelRouter router = new ModelRouter(List.of(anthropic, openai));

        LlmResponse r1 = router.createMessage(canonicalReq("claude-sonnet-4-6"));
        LlmResponse r2 = router.createMessage(canonicalReq("gpt-4o-mini"));
        LlmResponse r3 = router.createMessage(canonicalReq("o3-preview"));

        assertThat(r1.firstText()).isEqualTo("hello from anthropic");
        assertThat(r2.firstText()).isEqualTo("hello from openai");
        assertThat(r3.firstText()).isEqualTo("hello from openai");

        assertThat(anthropic.canonicalCalls).isEqualTo(1);
        assertThat(openai.canonicalCalls).isEqualTo(2);
    }

    @Test
    void llmClientContractIsSameInstanceAsAnthropicClient() {
        StubProvider anthropic = new StubProvider("anthropic", List.of("claude-"), "ok");
        ModelRouter router = new ModelRouter(List.of(anthropic));

        // The router satisfies both contracts — same runtime instance.
        AnthropicClient asLegacy = router;
        LlmClient asCanonical = router;
        assertThat(asLegacy).isSameAs(asCanonical);
    }

    @Test
    void assistantWithToolCall_preservedThroughRouter() {
        ObjectNode input = json.createObjectNode().put("cmd", "ls");
        StubProvider anthropic = new StubProvider("anthropic", List.of("claude-"),
                LlmResponse.builder()
                        .id("msg_x")
                        .model("claude-sonnet-4-6")
                        .content(List.of(new LlmToolCall("toolu_1", "bash", input)))
                        .stopReason(LlmStopReason.TOOL_CALLS)
                        .build());

        ModelRouter router = new ModelRouter(List.of(anthropic));

        LlmResponse res = router.createMessage(canonicalReq("claude-sonnet-4-6"));
        assertThat(res.needsToolExecution()).isTrue();
        assertThat(res.toolCalls()).hasSize(1);
        assertThat(res.toolCalls().get(0).getName()).isEqualTo("bash");
    }

    // ────────────────────────────────────────────────────────────

    private LlmRequest canonicalReq(String model) {
        return LlmRequest.builder()
                .model(model)
                .maxTokens(256)
                .messages(List.of(LlmMessage.userText("hi")))
                .build();
    }

    /**
     * Test stub that satisfies both the legacy Anthropic-shaped and the P2 canonical
     * {@link ModelProvider} entrypoints. Counts calls per entrypoint separately so
     * tests can assert which path was taken.
     */
    private static class StubProvider implements ModelProvider {
        private final String providerName;
        private final List<String> prefixes;
        private final LlmResponse canonicalResponse;
        int canonicalCalls = 0;
        int legacyCalls = 0;

        StubProvider(String name, List<String> prefixes, String responseText) {
            this(name, prefixes,
                    LlmResponse.builder()
                            .id("msg_stub")
                            .model("stub-model")
                            .content(List.of(new LlmText(responseText)))
                            .stopReason(LlmStopReason.END_TURN)
                            .build());
        }

        StubProvider(String name, List<String> prefixes, LlmResponse canonicalResponse) {
            this.providerName = name;
            this.prefixes = prefixes;
            this.canonicalResponse = canonicalResponse;
        }

        @Override
        public String name() { return providerName; }

        @Override
        public List<String> modelPrefixes() { return prefixes; }

        @Override
        public CreateMessageResponse createMessage(
                com.xilidou.jooj.http.dto.CreateMessageRequest req) {
            legacyCalls++;
            return new CreateMessageResponse("msg_stub", "message", "assistant",
                    List.of(new TextBlock("legacy path")),
                    req.getModel(), "end_turn", null, new Usage(0, 0, null, null));
        }

        @Override
        public LlmResponse createMessage(LlmRequest req) {
            canonicalCalls++;
            return canonicalResponse;
        }
    }
}
