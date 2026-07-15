package com.xilidou.jooj.http;

import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;
import com.xilidou.jooj.llm.domain.LlmStopReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 单元测试:验证 OpenAI provider 元数据、路由、和构造器逻辑(P2 Step H)。
 *
 * <p>不发真实 HTTP —— 验证:
 * <ul>
 *   <li>name / modelPrefixes 正确(gpt/o1/o3/o4/chatgpt 5 类前缀)</li>
 *   <li>ModelRouter 按 prefix 路由 gpt-* / o1-* / o3-* 到 openai provider</li>
 *   <li>openai provider 不影响 claude-* / deepseek-* 路由</li>
 *   <li>OpenAiHttpClient 全参构造器正确接受自定义 name/prefixes(供 Groq / Azure proxy 复用)</li>
 * </ul>
 *
 * <p>真实 API 调用见 {@link OpenAiE2ETest}(gated on OPENAI_API_KEY)。
 */
class OpenAiProviderTest {

    @Test
    @DisplayName("默认构造器: name=openai, prefixes=[gpt-, o1-, o3-, o4-, chatgpt-]")
    void defaultNameAndPrefixes() {
        var client = new OpenAiHttpClient(
                OpenAiHttpClient.defaultOkHttpClient(),
                com.xilidou.jooj.config.JsonMappers.newMapper(),
                "https://api.openai.com",
                new BearerTokenAuth("sk-test")
        );

        assertThat(client.name()).isEqualTo("openai");
        assertThat(client.modelPrefixes()).containsExactly("gpt-", "o1-", "o3-", "o4-", "chatgpt-");
        assertThat(client.getBaseUrl()).isEqualTo("https://api.openai.com");
    }

    @Test
    @DisplayName("全参构造器: name/prefixes 可自定义(Groq / Azure proxy 场景)")
    void customNameAndPrefixes() {
        var client = new OpenAiHttpClient(
                OpenAiHttpClient.defaultOkHttpClient(),
                com.xilidou.jooj.config.JsonMappers.newMapper(),
                "https://api.groq.com/openai",
                new BearerTokenAuth("gsk-test"),
                "groq",
                List.of("llama-", "mixtral-")
        );

        assertThat(client.name()).isEqualTo("groq");
        assertThat(client.modelPrefixes()).containsExactly("llama-", "mixtral-");
        assertThat(client.getBaseUrl()).isEqualTo("https://api.groq.com/openai");
    }

    @Test
    @DisplayName("Legacy wire entrypoint 抛 UnsupportedOperationException(OpenAI 不消费 Anthropic wire)")
    void legacy_wire_entrypoint_throws() {
        var client = OpenAiHttpClient.builder()
                .baseUrl("https://api.openai.com")
                .auth(new BearerTokenAuth("sk-test"))
                .build();

        assertThatThrownBy(() -> client.createMessage(
                com.xilidou.jooj.http.dto.CreateMessageRequest.builder()
                        .model("gpt-4o-mini").maxTokens(10).build()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("canonical createMessage(LlmRequest)");
    }

    // ── ModelRouter 路由集成 ──────────────────────────────────────

    @Test
    @DisplayName("ModelRouter 将 gpt-4o-mini 路由到 openai provider")
    void routerRoutesGptModels() {
        var anthropic = new StubProvider("anthropic", List.of("claude-"));
        var openai = new StubProvider("openai", List.of("gpt-", "o1-", "o3-", "o4-", "chatgpt-"));
        var router = new ModelRouter(List.of(anthropic, openai));

        router.createMessage(canonical("gpt-4o-mini"));

        assertThat(openai.canonicalCalls).isEqualTo(1);
        assertThat(anthropic.canonicalCalls).isZero();
    }

    @Test
    @DisplayName("ModelRouter 将 o1-preview 路由到 openai provider(reasoning 模型)")
    void routerRoutesReasoningModels() {
        var anthropic = new StubProvider("anthropic", List.of("claude-"));
        var openai = new StubProvider("openai", List.of("gpt-", "o1-", "o3-", "o4-"));
        var router = new ModelRouter(List.of(anthropic, openai));

        router.createMessage(canonical("o1-preview"));
        router.createMessage(canonical("o3-mini"));
        router.createMessage(canonical("o4-preview"));

        assertThat(openai.canonicalCalls).isEqualTo(3);
        assertThat(anthropic.canonicalCalls).isZero();
    }

    @Test
    @DisplayName("claude- 前缀仍正确路由到 anthropic(不被 openai 抢走)")
    void routerStillRoutesClaudeToAnthropic() {
        var anthropic = new StubProvider("anthropic", List.of("claude-"));
        var openai = new StubProvider("openai", List.of("gpt-", "o1-", "o3-"));
        var router = new ModelRouter(List.of(anthropic, openai));

        router.createMessage(canonical("claude-sonnet-4-6"));

        assertThat(anthropic.canonicalCalls).isEqualTo(1);
        assertThat(openai.canonicalCalls).isZero();
    }

    @Test
    @DisplayName("三 provider 共存: anthropic / deepseek / openai 混合路由正确")
    void triple_provider_mix() {
        var anthropic = new StubProvider("anthropic", List.of("claude-"));
        var deepseek = new StubProvider("deepseek", List.of("deepseek-"));
        var openai = new StubProvider("openai", List.of("gpt-", "o1-", "o3-"));
        var router = new ModelRouter(List.of(anthropic, deepseek, openai));

        router.createMessage(canonical("gpt-4o-mini"));
        router.createMessage(canonical("claude-sonnet-4-6"));
        router.createMessage(canonical("deepseek-chat"));
        router.createMessage(canonical("o1-preview"));
        router.createMessage(canonical("claude-3-haiku-20240307"));

        assertThat(openai.canonicalCalls).isEqualTo(2);   // gpt-4o-mini + o1-preview
        assertThat(anthropic.canonicalCalls).isEqualTo(2); // 2 claude 变种
        assertThat(deepseek.canonicalCalls).isEqualTo(1);
    }

    // ── Helper ──────────────────────────────────────────────────

    private LlmRequest canonical(String model) {
        return LlmRequest.builder()
                .model(model)
                .maxTokens(64)
                .messages(List.of(LlmMessage.userText("test")))
                .build();
    }

    private static class StubProvider implements ModelProvider {
        private final String providerName;
        private final List<String> prefixes;
        int canonicalCalls = 0;

        StubProvider(String name, List<String> prefixes) {
            this.providerName = name;
            this.prefixes = prefixes;
        }

        @Override
        public String name() { return providerName; }

        @Override
        public List<String> modelPrefixes() { return prefixes; }

        @Override
        public com.xilidou.jooj.http.dto.CreateMessageResponse createMessage(
                com.xilidou.jooj.http.dto.CreateMessageRequest req) {
            throw new UnsupportedOperationException("legacy path not exercised in this test");
        }

        @Override
        public LlmResponse createMessage(LlmRequest req) {
            canonicalCalls++;
            return LlmResponse.builder()
                    .id("msg_stub")
                    .model(req.getModel())
                    .content(List.of())
                    .stopReason(LlmStopReason.END_TURN)
                    .build();
        }
    }
}
