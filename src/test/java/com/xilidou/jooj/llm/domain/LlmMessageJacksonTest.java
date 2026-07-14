package com.xilidou.jooj.llm.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xilidou.jooj.config.JsonMappers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip test for canonical LLM domain types through Jackson.
 *
 * <p>Two goals:
 * <ol>
 *   <li>Prove that the {@link LlmContent} sealed hierarchy round-trips via its
 *       {@code kind} discriminator — session storage depends on this in Step G.</li>
 *   <li>Prove that an unknown {@code kind} lands as {@link LlmOpaque} instead of
 *       blowing up (forward-compat when a future adapter emits a new block shape).</li>
 * </ol>
 */
class LlmMessageJacksonTest {

    private final ObjectMapper json = JsonMappers.newMapper();

    @Test
    void roundTrip_userMessageWithText() throws Exception {
        LlmMessage msg = LlmMessage.userText("hello world");
        String out = json.writeValueAsString(msg);
        LlmMessage back = json.readValue(out, LlmMessage.class);

        assertThat(back.getRole()).isEqualTo(LlmRole.USER);
        assertThat(back.getContent()).hasSize(1);
        assertThat(back.getContent().get(0)).isInstanceOf(LlmText.class);
        assertThat(((LlmText) back.getContent().get(0)).getText()).isEqualTo("hello world");
    }

    @Test
    void roundTrip_assistantWithTextAndToolCall() throws Exception {
        ObjectNode input = json.createObjectNode().put("path", "/tmp");
        LlmMessage msg = LlmMessage.assistant(List.of(
                new LlmText("I'll list files"),
                new LlmToolCall("call_1", "ls", input)
        ));
        String out = json.writeValueAsString(msg);
        LlmMessage back = json.readValue(out, LlmMessage.class);
        assertThat(back.getRole()).isEqualTo(LlmRole.ASSISTANT);
        assertThat(back.getContent()).hasSize(2);
        assertThat(back.getContent().get(1)).isInstanceOf(LlmToolCall.class);
        assertThat(((LlmToolCall) back.getContent().get(1)).getName()).isEqualTo("ls");
        assertThat(((LlmToolCall) back.getContent().get(1)).getInput().get("path").asText())
                .isEqualTo("/tmp");
    }

    @Test
    void roundTrip_toolMessageWithResult() throws Exception {
        LlmMessage msg = LlmMessage.toolResults(List.of(
                LlmToolResult.error("call_1", "command failed: no such file")
        ));
        String out = json.writeValueAsString(msg);
        LlmMessage back = json.readValue(out, LlmMessage.class);
        assertThat(back.getRole()).isEqualTo(LlmRole.TOOL);
        LlmToolResult r = (LlmToolResult) back.getContent().get(0);
        assertThat(r.getToolCallId()).isEqualTo("call_1");
        assertThat(r.isError()).isTrue();
        assertThat(r.getOutput()).contains("command failed");
    }

    @Test
    void roundTrip_thinkingCarriesVendorAndSignature() throws Exception {
        LlmThinking th = new LlmThinking("Let me think", "sig_1234", "anthropic");
        String out = json.writeValueAsString(th);
        LlmContent back = json.readValue(out, LlmContent.class);
        assertThat(back).isInstanceOf(LlmThinking.class);
        LlmThinking t = (LlmThinking) back;
        assertThat(t.getSignature()).isEqualTo("sig_1234");
        assertThat(t.getVendor()).isEqualTo("anthropic");
    }

    @Test
    void unknownKind_deserializesAsOpaque() throws Exception {
        // Simulate a future adapter emitting a shape jooj doesn't yet model.
        String jsonStr = "{\"kind\":\"image\",\"vendor\":\"anthropic\","
                + "\"type\":\"image\",\"raw\":{\"source\":\"https://x/y.png\"}}";
        LlmContent back = json.readValue(jsonStr, LlmContent.class);
        assertThat(back).isInstanceOf(LlmOpaque.class);
        LlmOpaque op = (LlmOpaque) back;
        assertThat(op.getVendor()).isEqualTo("anthropic");
        assertThat(op.getRaw()).containsEntry("source", "https://x/y.png");
    }

    @Test
    void opaqueBlock_roundTrip() throws Exception {
        LlmOpaque op = new LlmOpaque("anthropic", "image",
                new java.util.HashMap<>(Map.of("url", "https://x/y.png")));
        String out = json.writeValueAsString(op);
        LlmContent back = json.readValue(out, LlmContent.class);
        assertThat(back).isInstanceOf(LlmOpaque.class);
        assertThat(((LlmOpaque) back).getType()).isEqualTo("image");
        assertThat(((LlmOpaque) back).getRaw()).containsEntry("url", "https://x/y.png");
    }
}
