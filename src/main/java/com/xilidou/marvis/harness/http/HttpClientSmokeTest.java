package com.xilidou.marvis.harness.http;

import com.xilidou.marvis.harness.http.dto.CreateMessageRequest;
import com.xilidou.marvis.harness.http.dto.CreateMessageResponse;
import com.xilidou.marvis.harness.http.dto.MessageParam;
import io.github.cdimascio.dotenv.Dotenv;

import java.util.List;

/**
 * 验证 {@link AnthropicHttpClient} + DI 链路。
 *
 * <p>对比 {@link SmokeTest}：
 * <ul>
 *   <li>SmokeTest：手写 OkHttp 调用、手写 Map 拼请求</li>
 *   <li>本类：用 AnthropicHttpClient + CreateMessageRequest 强类型</li>
 * </ul>
 *
 * <p>跑通这个就证明：
 * <ol>
 *   <li>构造器注入工作正常（http / json / baseUrl / auth 全部正确传入）</li>
 *   <li>fromEnv 工厂方法能从 .env 读配置</li>
 *   <li>HttpAuth 策略模式正确派发 header（双 auth 二选一）</li>
 *   <li>请求侧 DTO（CreateMessageRequest / MessageParam）正确序列化</li>
 *   <li>响应侧 DTO（CreateMessageResponse）正确反序列化</li>
 * </ol>
 *
 * <p>运行：
 * <pre>
 *   ./mvnw -q exec:java \
 *     -Dexec.mainClass="com.xilidou.marvis.harness.http.HttpClientSmokeTest"
 * </pre>
 */
public class HttpClientSmokeTest {

    public static void main(String[] args) {
        // ── Step 1: 装配（DI 链路）──────────────────────────────
        System.out.println("── Step 1/3: 装配 AnthropicHttpClient ──");
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        AnthropicHttpClient client = AnthropicHttpClient.fromEnv(dotenv);
        String model = readEnv(dotenv, "MODEL_ID");

        System.out.println("baseUrl   = " + client.getBaseUrl());
        System.out.println("auth mode = " + client.getAuth().describe());
        System.out.println("model     = " + model);
        System.out.println();

        // ── Step 2: 构造请求（强类型 DTO）─────────────────────────
        System.out.println("── Step 2/3: 构造请求 ──");
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model(model)
                .maxTokens(50)
                .messages(List.of(
                        MessageParam.user("Just say 'pong' and nothing else.")
                ))
                .build();
        System.out.println("request: " + request);
        System.out.println();

        // ── Step 3: 调用（一行）─────────────────────────────────
        System.out.println("── Step 3/3: 调用 createMessage ──");
        CreateMessageResponse resp = client.createMessage(request);

        System.out.println("text         : " + resp.firstText());
        System.out.println("stop_reason  : " + resp.getStopReason());
        System.out.println("model echo   : " + resp.getModel());
        resp.usageOpt().ifPresent(u -> {
            System.out.println("input_tokens : " + u.getInputTokens());
            System.out.println("output_tokens: " + u.getOutputTokens());
            System.out.printf("cost (USD)   : $%.6f%n", u.estimatedCostUsd());
        });

        System.out.println();
        System.out.println("✅ AnthropicHttpClient + DI 链路验证通过");
    }

    private static String readEnv(Dotenv dotenv, String key) {
        String v = dotenv.get(key);
        if (v == null || v.isBlank()) v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing env: " + key);
        }
        return v;
    }
}
