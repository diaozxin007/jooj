package com.xilidou.jooj.http;

import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 单元测试:验证 {@link ModelRouter} 的路由逻辑。
 *
 * <ul>
 *   <li>按 prefix 路由到正确 provider</li>
 *   <li>无匹配时 fallback 到默认 provider</li>
 *   <li>空 / null model 走默认</li>
 *   <li>缓存命中(同 model 不重复遍历)</li>
 * </ul>
 */
class ModelRouterTest {

    private StubProvider anthropicProvider;
    private StubProvider openaiProvider;
    private ModelRouter router;

    @BeforeEach
    void setUp() {
        anthropicProvider = new StubProvider("anthropic", List.of("claude-"));
        openaiProvider = new StubProvider("openai", List.of("gpt-", "o1-"));
        router = new ModelRouter(List.of(anthropicProvider, openaiProvider));
    }

    // ── 路由正确性 ──────────────────────────────────────────────

    @Test
    @DisplayName("claude- 前缀路由到 anthropic provider")
    void routesClaude() {
        var req = createRequest("claude-sonnet-4-6");
        router.createMessage(req);

        assertThat(anthropicProvider.callCount).isEqualTo(1);
        assertThat(openaiProvider.callCount).isZero();
    }

    @Test
    @DisplayName("gpt- 前缀路由到 openai provider")
    void routesGpt() {
        var req = createRequest("gpt-4o");
        router.createMessage(req);

        assertThat(openaiProvider.callCount).isEqualTo(1);
        assertThat(anthropicProvider.callCount).isZero();
    }

    @Test
    @DisplayName("o1- 前缀路由到 openai provider")
    void routesO1() {
        var req = createRequest("o1-preview");
        router.createMessage(req);

        assertThat(openaiProvider.callCount).isEqualTo(1);
        assertThat(anthropicProvider.callCount).isZero();
    }

    // ── Fallback ────────────────────────────────────────────────

    @Test
    @DisplayName("未知前缀 fallback 到默认 provider(第一个注册的)")
    void fallbackToDefault() {
        var req = createRequest("llama-3.1-70b");
        router.createMessage(req);

        assertThat(anthropicProvider.callCount).isEqualTo(1);
        assertThat(openaiProvider.callCount).isZero();
    }

    @Test
    @DisplayName("null model fallback 到默认 provider")
    void nullModelFallback() {
        var req = createRequest(null);
        router.createMessage(req);

        assertThat(anthropicProvider.callCount).isEqualTo(1);
    }

    @Test
    @DisplayName("空字符串 model fallback 到默认 provider")
    void emptyModelFallback() {
        var req = createRequest("");
        router.createMessage(req);

        assertThat(anthropicProvider.callCount).isEqualTo(1);
    }

    // ── 初始化校验 ──────────────────────────────────────────────

    @Test
    @DisplayName("空 provider list 抛 IllegalArgumentException")
    void emptyProvidersThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelRouter(List.of()))
                .withMessageContaining("At least one");
    }

    @Test
    @DisplayName("null provider list 抛 IllegalArgumentException")
    void nullProvidersThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelRouter(null));
    }

    // ── 可观测性 ────────────────────────────────────────────────

    @Test
    @DisplayName("getProviders 返回注册列表")
    void getProviders() {
        assertThat(router.getProviders()).hasSize(2);
        assertThat(router.getProviders().get(0).name()).isEqualTo("anthropic");
        assertThat(router.getProviders().get(1).name()).isEqualTo("openai");
    }

    @Test
    @DisplayName("getDefaultProvider 返回第一个注册的")
    void getDefaultProvider() {
        assertThat(router.getDefaultProvider().name()).isEqualTo("anthropic");
    }

    // ── resolveProvider 缓存 ────────────────────────────────────

    @Test
    @DisplayName("同一 model 多次调用,provider 路由一致")
    void cacheConsistency() {
        var req = createRequest("gpt-4o");
        router.createMessage(req);
        router.createMessage(req);
        router.createMessage(req);

        assertThat(openaiProvider.callCount).isEqualTo(3);
        assertThat(anthropicProvider.callCount).isZero();
    }

    // ── Helper ──────────────────────────────────────────────────

    private CreateMessageRequest createRequest(String model) {
        return CreateMessageRequest.builder()
                .model(model)
                .maxTokens(100)
                .build();
    }

    /**
     * 轻量 stub —— 只记录调用次数,返回固定 response。
     */
    private static class StubProvider implements ModelProvider {
        private final String providerName;
        private final List<String> prefixes;
        int callCount = 0;

        StubProvider(String name, List<String> prefixes) {
            this.providerName = name;
            this.prefixes = prefixes;
        }

        @Override
        public String name() {
            return providerName;
        }

        @Override
        public List<String> modelPrefixes() {
            return prefixes;
        }

        @Override
        public CreateMessageResponse createMessage(CreateMessageRequest req) {
            callCount++;
            return new CreateMessageResponse(
                    "msg_stub",        // id
                    "message",         // type
                    "assistant",       // role
                    List.of(),         // content
                    req.getModel(),    // model
                    "end_turn",        // stopReason
                    null,              // stopSequence
                    null               // usage
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
