package com.xilidou.marvis.harness.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.harness.JacksonConfig;
import com.xilidou.marvis.harness.http.dto.CreateMessageResponse;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OkHttp 链路冒烟测试 - 不依赖 Loop / Harness，只验证：
 * <ol>
 *   <li>能从 .env / 系统环境变量读到配置</li>
 *   <li>OkHttp 能发出符合 Anthropic 协议的 POST 请求</li>
 *   <li>响应能被你写的 DTO 正确反序列化</li>
 * </ol>
 *
 * <p>运行方式：
 * <pre>
 *   ./mvnw exec:java \
 *     -Dexec.mainClass="com.xilidou.marvis.harness.http.SmokeTest" \
 *     -Dexec.classpathScope=test
 * </pre>
 *
 * <p>或在 IDE 里直接 Run。
 *
 * <p>预期输出：
 * <pre>
 *   ── Step 1/3: 读取配置 ──
 *   model     = Claude-Sonnet-4.6-proxy
 *   baseUrl   = http://proxy.example.com:8787
 *   auth mode = Bearer (proxy proxy)
 *
 *   ── Step 2/3: 发送请求 ──
 *   POST http://proxy.example.com:8787/v1/messages
 *   request body (180 bytes):
 *   {"model":"...","max_tokens":50,"messages":[{"role":"user","content":"Just say pong"}]}
 *
 *   ── Step 3/3: 解析响应 ──
 *   HTTP 200 OK
 *   text         : pong
 *   stop_reason  : end_turn
 *   input_tokens : 12
 *   output_tokens: 4
 *   cost (USD)   : $0.000096
 *
 *   ✅ OkHttp 链路验证通过
 * </pre>
 */
public class SmokeTest {

    public static void main(String[] args) throws Exception {
        // ── Step 1/3: 读取配置 ────────────────────────────────────
        System.out.println("── Step 1/3: 读取配置 ──");
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()      // .env 不存在时不抛异常，只读系统环境变量
                .load();

        String model = required(dotenv, "MODEL_ID");
        String baseUrl = optional(dotenv, "ANTHROPIC_BASE_URL", "https://api.anthropic.com");

        // 双 auth 策略：API_KEY 优先（官方），否则用 AUTH_TOKEN（公司代理）
        String apiKey = optional(dotenv, "ANTHROPIC_API_KEY", null);
        String authToken = optional(dotenv, "ANTHROPIC_AUTH_TOKEN", null);

        if (isBlank(apiKey) && isBlank(authToken)) {
            System.err.println("❌ 缺少认证：请设置 ANTHROPIC_API_KEY 或 ANTHROPIC_AUTH_TOKEN");
            System.exit(1);
        }
        String authMode = !isBlank(apiKey) ? "x-api-key (Anthropic 官方)" : "Bearer (proxy proxy)";

        System.out.println("model     = " + model);
        System.out.println("baseUrl   = " + baseUrl);
        System.out.println("auth mode = " + authMode);
        System.out.println();

        // ── Step 2/3: 发送请求 ────────────────────────────────────
        System.out.println("── Step 2/3: 发送请求 ──");
        ObjectMapper json = JacksonConfig.newMapper();

        // 手动构造请求体，不依赖 CreateMessageRequest（Request 侧 DTO 还没写）
        // 这样可以独立验证响应链路
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 50,
                "messages", java.util.List.of(
                        Map.of("role", "user", "content", "Just say 'pong' and nothing else.")
                )
        );
        String body = json.writeValueAsString(requestBody);
        System.out.println("POST " + baseUrl + "/v1/messages");
        System.out.println("request body (" + body.length() + " bytes):");
        System.out.println(body);
        System.out.println();

        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        Request.Builder reqBuilder = new Request.Builder()
                .url(baseUrl + "/v1/messages")
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(RequestBody.create(body, MediaType.parse("application/json")));

        // 双 auth header 二选一
        if (!isBlank(apiKey)) {
            reqBuilder.header("x-api-key", apiKey);
        } else {
            reqBuilder.header("Authorization", "Bearer " + authToken);
        }

        // ── Step 3/3: 解析响应 ────────────────────────────────────
        System.out.println("── Step 3/3: 解析响应 ──");
        try (Response resp = http.newCall(reqBuilder.build()).execute()) {
            String respBody = resp.body() != null ? resp.body().string() : "";

            System.out.println("HTTP " + resp.code() + " " + resp.message());

            if (!resp.isSuccessful()) {
                System.err.println("❌ 请求失败");
                System.err.println("response body: " + respBody);
                System.exit(2);
            }

            // 关键：用你写的 DTO 反序列化真实响应
            CreateMessageResponse parsed = json.readValue(respBody, CreateMessageResponse.class);

            System.out.println("text         : " + parsed.firstText());
            System.out.println("stop_reason  : " + parsed.getStopReason());
            System.out.println("model echo   : " + parsed.getModel());

            parsed.usageOpt().ifPresent(u -> {
                System.out.println("input_tokens : " + u.getInputTokens());
                System.out.println("output_tokens: " + u.getOutputTokens());
                System.out.printf( "cost (USD)   : $%.6f%n", u.estimatedCostUsd());
            });

            System.out.println();
            System.out.println("✅ OkHttp 链路验证通过");
        }
    }

    // ── 小工具 ────────────────────────────────────────────────
    private static String required(Dotenv dotenv, String key) {
        String value = optional(dotenv, key, null);
        if (isBlank(value)) {
            System.err.println("❌ 必填环境变量缺失: " + key);
            System.exit(1);
        }
        return value;
    }

    private static String optional(Dotenv dotenv, String key, String defaultValue) {
        // dotenv 默认会查 .env 和系统环境变量
        String value = dotenv.get(key);
        if (isBlank(value)) value = System.getenv(key);
        return isBlank(value) ? defaultValue : value;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
