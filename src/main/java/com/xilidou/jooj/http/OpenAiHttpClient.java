package com.xilidou.jooj.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.llm.adapter.OpenAiChatAdapter;
import com.xilidou.jooj.llm.domain.LlmException;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * OpenAI Chat Completions provider —— {@code POST /v1/chat/completions}。
 *
 * <p>P2 Step H(2026-07-15):新增 OpenAI 原生 provider,和
 * {@link AnthropicHttpClient} 并列注册到 {@link ModelRouter},按 model ID 前缀
 * ({@code gpt-} / {@code o1-} / {@code o3-} / {@code o4-})路由。
 *
 * <h3>核心设计</h3>
 * <ul>
 *   <li><b>只实现 canonical entrypoint</b>:{@link #createMessage(LlmRequest)} 走
 *       {@link OpenAiChatAdapter#toWire} → HTTP POST → {@link OpenAiChatAdapter#toDomain}
 *       Legacy {@link #createMessage(com.xilidou.jooj.http.dto.CreateMessageRequest)}
 *       签名(@Deprecated)在 {@link ModelProvider} 契约里保留,本 provider 抛
 *       UnsupportedOperationException —— 只有 Anthropic 系(claude + deepseek)才走
 *       wire 类型;OpenAI 不需要,adapter 直接消费 canonical。</li>
 *   <li><b>Bearer auth</b>:OpenAI 用 {@code Authorization: Bearer sk-...}(见
 *       {@link BearerTokenAuth}),不像 Anthropic 走 {@code x-api-key}。</li>
 *   <li><b>纯 DI</b>:构造函数注入所有依赖,不读环境变量、不读配置文件</li>
 *   <li><b>由 {@link OpenAiProviderConfiguration} Bean 装配</b>,{@code jooj.openai.api-key}
 *       非空才注册进容器</li>
 * </ul>
 *
 * <h3>为什么不复用 AnthropicHttpClient 骨架</h3>
 *
 * <p>plan §七 · 复用参考显式说 "AnthropicHttpClient 的 OkHttp / auth / 反序列化骨架被
 * OpenAiHttpClient 借鉴复制(不共享父类,避免误抽象)"。两家 provider 的:
 * <ul>
 *   <li>endpoint 不同({@code /v1/messages} vs {@code /v1/chat/completions})</li>
 *   <li>auth header 不同({@code x-api-key} vs {@code Authorization: Bearer})</li>
 *   <li>请求体形态不同(Anthropic wire DTO 序列化 vs 直接送 canonical → adapter 生成的 JsonNode)</li>
 *   <li>响应解析不同(Anthropic wire DTO 反序列化 vs adapter 消费 JsonNode)</li>
 *   <li>error classification 不同(见各自 adapter.classify)</li>
 * </ul>
 * 抽父类会让每个 hook 都变泛型,读起来更乱;直接复制骨架 ~ 60 行,清晰。
 */
@Slf4j
public class OpenAiHttpClient implements ModelProvider {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
    private static final String CHAT_ENDPOINT = "/v1/chat/completions";

    private final OkHttpClient http;
    private final ObjectMapper json;
    @Getter
    private final String baseUrl;
    @Getter
    private final HttpAuth auth;
    private final String providerName;
    private final List<String> prefixes;
    private final OpenAiChatAdapter adapter;

    /**
     * 默认构造:providerName="openai",prefixes=["gpt-", "o1-", "o3-", "o4-"]。
     *
     * @param http    OkHttp 客户端(应复用)
     * @param json    Jackson ObjectMapper
     * @param baseUrl 不带尾斜杠的 API 根,如 {@code https://api.openai.com}
     * @param auth    认证策略(通常 {@link BearerTokenAuth})
     */
    public OpenAiHttpClient(OkHttpClient http, ObjectMapper json, String baseUrl, HttpAuth auth) {
        this(http, json, baseUrl, auth, "openai",
                List.of("gpt-", "o1-", "o3-", "o4-", "chatgpt-"));
    }

    /**
     * 完整构造器 —— 支持以任意身份注册为 {@link ModelProvider}。
     * 适用于 OpenAI Chat-Completions 兼容协议的第三方 provider(如 Groq / Azure OpenAI proxy)。
     */
    public OpenAiHttpClient(OkHttpClient http, ObjectMapper json, String baseUrl,
                            HttpAuth auth, String providerName, List<String> prefixes) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.auth = Objects.requireNonNull(auth, "auth");
        this.providerName = Objects.requireNonNull(providerName, "providerName");
        this.prefixes = List.copyOf(Objects.requireNonNull(prefixes, "prefixes"));
        this.adapter = new OpenAiChatAdapter(this.json);
    }

    // ── ModelProvider 契约 ──────────────────────────────────────

    @Override
    public String name() {
        return providerName;
    }

    @Override
    public List<String> modelPrefixes() {
        return prefixes;
    }

    /**
     * Legacy wire entrypoint —— OpenAI 不消费 Anthropic wire DTO,一律走 canonical。
     * 保留是为满足 {@link ModelProvider} 契约(@Deprecated,Steps C-G 迁完后可从接口删除)。
     */
    @Override
    @Deprecated
    public com.xilidou.jooj.http.dto.CreateMessageResponse createMessage(
            com.xilidou.jooj.http.dto.CreateMessageRequest req) {
        throw new UnsupportedOperationException(
                "OpenAiHttpClient does not consume Anthropic wire types. "
                        + "Use the canonical createMessage(LlmRequest) instead. "
                        + "This method is only present to satisfy the ModelProvider legacy contract "
                        + "and will be removed once the contract is cleaned up.");
    }

    /**
     * P2 canonical entrypoint. Translates via {@link OpenAiChatAdapter} and executes the
     * HTTP POST to {@code /v1/chat/completions}; classifies any non-2xx into
     * {@link LlmException}(kind 已在 adapter.classify 里 mapped:context_length_exceeded /
     * insufficient_quota / rate_limit / 5xx / etc).
     */
    @Override
    public LlmResponse createMessage(LlmRequest req) throws LlmException {
        JsonNode wireBody = adapter.toWire(req);
        String body;
        try {
            body = json.writeValueAsString(wireBody);
        } catch (IOException e) {
            log.warn("[openai-http] JSON serialize error: {}", e.toString());
            throw adapter.classify(0, "JSON serialize error: " + e.getMessage(), e);
        }

        // 与 anthropic-http 对齐:请求 DEBUG + 失败 WARN + 成功 DEBUG。
        // digest 复用 HttpBodyDigest —— 它按 JSON tree 截断 signature / 大 tool_result,
        // 对 OpenAI 的 messages / tool_calls 结构同样有效。
        log.debug("[openai-http] -> POST {} bytes={} body={}",
                CHAT_ENDPOINT, body.length(), HttpBodyDigest.digest(body, json));

        Request.Builder reqBuilder = new Request.Builder()
                .url(baseUrl + CHAT_ENDPOINT)
                .header("content-type", "application/json")
                .post(RequestBody.create(body, JSON_MEDIA_TYPE));
        auth.apply(reqBuilder);

        try (Response resp = http.newCall(reqBuilder.build()).execute()) {
            String respBody = resp.body() != null ? resp.body().string() : "";

            if (!resp.isSuccessful()) {
                log.warn("[openai-http] <- status={} bytes={} body={}",
                        resp.code(), respBody.length(),
                        HttpBodyDigest.digest(respBody, json));
                throw adapter.classify(resp.code(), respBody, null);
            }
            log.debug("[openai-http] <- status={} bytes={} body={}",
                    resp.code(), respBody.length(),
                    HttpBodyDigest.digest(respBody, json));
            JsonNode respJson = json.readTree(respBody);
            return adapter.toDomain(respJson);
        } catch (IOException e) {
            log.warn("[openai-http] IO error: {}", e.toString());
            throw adapter.classify(0, "IO error: " + e.getMessage(), e);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ────────────────────────────────────────────────────────────
    //  静态工厂 + Builder(测试 / 非 Spring 场景用)
    // ────────────────────────────────────────────────────────────

    public static OkHttpClient defaultOkHttpClient() {
        // 复用 Anthropic 侧的默认超时(10s connect / 120s read / 30s write)
        return AnthropicHttpClient.defaultOkHttpClient();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private OkHttpClient http;
        private ObjectMapper json;
        private String baseUrl = "https://api.openai.com";
        private HttpAuth auth;

        public Builder okHttpClient(OkHttpClient http) {
            this.http = http;
            return this;
        }

        public Builder objectMapper(ObjectMapper json) {
            this.json = json;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder auth(HttpAuth auth) {
            this.auth = auth;
            return this;
        }

        public OpenAiHttpClient build() {
            if (auth == null) {
                throw new IllegalStateException(
                        "auth is required (typically BearerTokenAuth for OpenAI)");
            }
            if (http == null) http = defaultOkHttpClient();
            if (json == null) json = JsonMappers.newMapper();
            return new OpenAiHttpClient(http, json, baseUrl, auth);
        }
    }
}
