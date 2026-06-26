package com.xilidou.jooj.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JacksonConfig;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * {@link AnthropicClient} 的 OkHttp 实现。
 *
 * <p>核心设计:
 * <ul>
 *   <li><b>有状态</b>:持有 {@link OkHttpClient}、{@link ObjectMapper}、{@link HttpAuth},
 *       一次构造多次复用(OkHttpClient 必须复用,否则浪费连接池)</li>
 *   <li><b>纯 DI</b>:构造函数注入所有依赖,不读环境变量、不读配置文件</li>
 *   <li><b>切片 C 之后:由 {@link com.xilidou.jooj.http.HttpClientConfig#anthropicClient}
 *       Bean 装配</b>,fromEnv 工厂方法已移除</li>
 * </ul>
 *
 * <p>典型用法:
 * <pre>
 *   // 路径 1:Spring 容器自动装配(默认)
 *   @Autowired AnthropicClient client;
 *
 *   // 路径 2:测试 / 自定义场景
 *   AnthropicClient client = AnthropicHttpClient.builder()
 *       .baseUrl("https://api.anthropic.com")
 *       .auth(new ApiKeyAuth(apiKey))
 *       .build();
 * </pre>
 */
@Slf4j
public class AnthropicHttpClient implements AnthropicClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final OkHttpClient http;
    private final ObjectMapper json;
    // ── 暴露给测试和外部观察 ────────────────────────────────────
    @Getter
    private final String baseUrl;
    @Getter
    private final HttpAuth auth;

    /**
     * 全参构造器(DI 友好)。所有依赖必须由调用方提供。
     *
     * @param http    OkHttp 客户端(应复用,内部有连接池)
     * @param json    Jackson ObjectMapper(应使用 {@link JacksonConfig#newMapper()})
     * @param baseUrl 不带尾斜杠的 API 根,如 {@code https://api.anthropic.com}
     * @param auth    认证策略
     */
    public AnthropicHttpClient(OkHttpClient http, ObjectMapper json, String baseUrl, HttpAuth auth) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.auth = Objects.requireNonNull(auth, "auth");
    }

    @Override
    public CreateMessageResponse createMessage(CreateMessageRequest req) {
        try {
            String body = json.writeValueAsString(req);

            // 用 SLF4J debug 级别输出请求体。
            // 启用方式:在 logback.xml 把 com.xilidou.jooj.http 设为 DEBUG
            // 或传 -Dlogging.level.com.xilidou.jooj.http=DEBUG
            log.debug("Anthropic request ({} bytes): {}", body.length(), body);

            Request.Builder reqBuilder = new Request.Builder()
                    .url(baseUrl + "/v1/messages")
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .post(RequestBody.create(body, JSON_MEDIA_TYPE));

            auth.apply(reqBuilder);

            try (Response resp = http.newCall(reqBuilder.build()).execute()) {
                String respBody = resp.body() != null ? resp.body().string() : "";

                if (!resp.isSuccessful()) {
                    throw new AnthropicException(resp.code(), respBody);
                }
                return json.readValue(respBody, CreateMessageResponse.class);
            }
        } catch (IOException e) {
            throw new AnthropicException(0, "IO error: " + e.getMessage(), e);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ────────────────────────────────────────────────────────────
    //  静态工厂 + Builder(测试 / 非 Spring 场景用)
    // ────────────────────────────────────────────────────────────

    /**
     * 默认 OkHttp 客户端(10s 连接 / 120s 读 / 30s 写)。
     * Spring 场景由 {@link com.xilidou.jooj.http.HttpClientConfig#okHttpClient()}
     * 提供等价的 Bean。
     */
    public static OkHttpClient defaultOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Builder(每个字段都有合理默认值,最少传 1 个 auth 就能用)。
     * 用于测试或非 Spring 场景的临时构造。
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private OkHttpClient http;
        private ObjectMapper json;
        private String baseUrl = "https://api.anthropic.com";
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

        public Builder timeout(Duration connect, Duration read, Duration write) {
            this.http = new OkHttpClient.Builder()
                    .connectTimeout(connect.toMillis(), TimeUnit.MILLISECONDS)
                    .readTimeout(read.toMillis(), TimeUnit.MILLISECONDS)
                    .writeTimeout(write.toMillis(), TimeUnit.MILLISECONDS)
                    .build();
            return this;
        }

        public AnthropicHttpClient build() {
            if (auth == null) {
                throw new IllegalStateException(
                        "auth is required (use ApiKeyAuth or BearerTokenAuth)");
            }
            if (http == null) http = defaultOkHttpClient();
            if (json == null) json = JacksonConfig.newMapper();
            return new AnthropicHttpClient(http, json, baseUrl, auth);
        }
    }
}
