package com.xilidou.jooj.http;

import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 单元测试:验证 DeepSeek provider 元数据、路由、和配置注册逻辑。
 *
 * <p>不发真实 HTTP —— 验证:
 * <ul>
 *   <li>name / modelPrefixes 正确</li>
 *   <li>ModelRouter 按 prefix 路由 deepseek-* 到 deepseek provider</li>
 *   <li>deepseek provider 不影响 claude-* 路由</li>
 *   <li>AnthropicHttpClient 全参构造器正确接受自定义 name/prefixes</li>
 * </ul>
 */
class DeepSeekProviderTest {

    // ── AnthropicHttpClient 可配置身份 ───────────────────────────

    @Test
    @DisplayName("全参构造器: name/prefixes 可自定义")
    void customNameAndPrefixes() {
        var client = new AnthropicHttpClient(
                AnthropicHttpClient.defaultOkHttpClient(),
                com.xilidou.jooj.config.JsonMappers.newMapper(),
                "https://api.deepseek.com/anthropic",
                new ApiKeyAuth("sk-test"),
                "deepseek",
                List.of("deepseek-")
        );

        assertThat(client.name()).isEqualTo("deepseek");
        assertThat(client.modelPrefixes()).containsExactly("deepseek-");
        assertThat(client.getBaseUrl()).isEqualTo("https://api.deepseek.com/anthropic");
    }

    @Test
    @DisplayName("默认构造器: name=anthropic, prefixes=[claude-]")
    void defaultNameAndPrefixes() {
        var client = new AnthropicHttpClient(
                AnthropicHttpClient.defaultOkHttpClient(),
                com.xilidou.jooj.config.JsonMappers.newMapper(),
                "https://api.anthropic.com",
                new ApiKeyAuth("sk-test")
        );

        assertThat(client.name()).isEqualTo("anthropic");
        assertThat(client.modelPrefixes()).containsExactly("claude-");
    }

    // ── ModelRouter 路由集成 ──────────────────────────────────────

    @Test
    @DisplayName("ModelRouter 将 deepseek-chat 路由到 deepseek provider")
    void routerRoutesDeepseek() {
        var anthropic = new StubProvider("anthropic", List.of("claude-"));
        var deepseek = new StubProvider("deepseek", List.of("deepseek-"));
        var router = new ModelRouter(List.of(anthropic, deepseek));

        router.createMessage(CreateMessageRequest.builder()
                .model("deepseek-chat")
                .maxTokens(100)
                .build());

        assertThat(deepseek.callCount).isEqualTo(1);
        assertThat(anthropic.callCount).isZero();
    }

    @Test
    @DisplayName("ModelRouter 将 deepseek-reasoner 路由到 deepseek provider")
    void routerRoutesDeepseekReasoner() {
        var anthropic = new StubProvider("anthropic", List.of("claude-"));
        var deepseek = new StubProvider("deepseek", List.of("deepseek-"));
        var router = new ModelRouter(List.of(anthropic, deepseek));

        router.createMessage(CreateMessageRequest.builder()
                .model("deepseek-reasoner")
                .maxTokens(100)
                .build());

        assertThat(deepseek.callCount).isEqualTo(1);
        assertThat(anthropic.callCount).isZero();
    }

    @Test
    @DisplayName("claude- 前缀仍正确路由到 anthropic(不被 deepseek 抢走)")
    void routerStillRoutesClaudeToAnthropic() {
        var anthropic = new StubProvider("anthropic", List.of("claude-"));
        var deepseek = new StubProvider("deepseek", List.of("deepseek-"));
        var router = new ModelRouter(List.of(anthropic, deepseek));

        router.createMessage(CreateMessageRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(100)
                .build());

        assertThat(anthropic.callCount).isEqualTo(1);
        assertThat(deepseek.callCount).isZero();
    }

    @Test
    @DisplayName("多 provider 共存: 混合路由正确")
    void mixedRouting() {
        var anthropic = new StubProvider("anthropic", List.of("claude-"));
        var deepseek = new StubProvider("deepseek", List.of("deepseek-"));
        var router = new ModelRouter(List.of(anthropic, deepseek));

        router.createMessage(req("deepseek-chat"));
        router.createMessage(req("claude-sonnet-4-6"));
        router.createMessage(req("deepseek-reasoner"));
        router.createMessage(req("claude-3-haiku-20240307"));

        assertThat(deepseek.callCount).isEqualTo(2);
        assertThat(anthropic.callCount).isEqualTo(2);
    }

    // ── Helper ──────────────────────────────────────────────────

    private CreateMessageRequest req(String model) {
        return CreateMessageRequest.builder().model(model).maxTokens(100).build();
    }

    private static class StubProvider implements ModelProvider {
        private final String providerName;
        private final List<String> prefixes;
        int callCount = 0;

        StubProvider(String name, List<String> prefixes) {
            this.providerName = name;
            this.prefixes = prefixes;
        }

        @Override
        public String name() { return providerName; }

        @Override
        public List<String> modelPrefixes() { return prefixes; }

        @Override
        public CreateMessageResponse createMessage(CreateMessageRequest req) {
            callCount++;
            return new CreateMessageResponse(
                    "msg_stub", "message", "assistant",
                    List.of(), req.getModel(), "end_turn", null, null
            );
        }

        @Override
        public com.xilidou.jooj.llm.domain.LlmResponse createMessage(
                com.xilidou.jooj.llm.domain.LlmRequest req) {
            callCount++;
            return com.xilidou.jooj.llm.domain.LlmResponse.builder()
                    .id("msg_stub")
                    .model(req.getModel())
                    .content(List.of())
                    .stopReason(com.xilidou.jooj.llm.domain.LlmStopReason.END_TURN)
                    .build();
        }
    }
}
