package com.xilidou.marvis.harness.http;

import okhttp3.Request;

/**
 * Anthropic API 认证策略。
 *
 * <p>支持两种认证方式（实现类二选一）：
 * <ul>
 *   <li>{@link ApiKeyAuth} - 官方 Anthropic，header: {@code x-api-key}</li>
 *   <li>{@link BearerTokenAuth} - 公司代理（如 proxy），header: {@code Authorization: Bearer}</li>
 * </ul>
 *
 * <p>用 Strategy Pattern 是为了：
 * <ol>
 *   <li>类型安全：编译期就确定用哪种 auth，避免运行时 if-else</li>
 *   <li>可扩展：将来加 OAuth / mTLS 只需新增实现类</li>
 *   <li>可测试：测试时可以注入 NoOpAuth 跳过认证</li>
 * </ol>
 *
 * <p>典型用法：
 * <pre>
 *   HttpAuth auth = HttpAuth.fromEnv(dotenv);
 *   Request.Builder req = new Request.Builder().url(...);
 *   auth.apply(req);
 *   client.newCall(req.build()).execute();
 * </pre>
 */
public interface HttpAuth {

    /**
     * 把认证 header 应用到请求 Builder 上。
     *
     * @param requestBuilder OkHttp 请求 Builder（mutable）
     */
    void apply(Request.Builder requestBuilder);

    /**
     * 返回此 auth 的描述（用于日志，不要包含敏感信息）
     */
    String describe();

    /**
     * 工厂方法：按优先级从环境变量构造 auth。
     * <ol>
     *   <li>{@code ANTHROPIC_API_KEY} 存在 → {@link ApiKeyAuth}</li>
     *   <li>否则 {@code ANTHROPIC_AUTH_TOKEN} 存在 → {@link BearerTokenAuth}</li>
     *   <li>否则抛 {@link IllegalStateException}</li>
     * </ol>
     */
    static HttpAuth fromEnv(io.github.cdimascio.dotenv.Dotenv dotenv) {
        String apiKey = read(dotenv, "ANTHROPIC_API_KEY");
        if (apiKey != null) return new ApiKeyAuth(apiKey);

        String authToken = read(dotenv, "ANTHROPIC_AUTH_TOKEN");
        if (authToken != null) return new BearerTokenAuth(authToken);

        throw new IllegalStateException(
                "No authentication configured. Set ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN.");
    }

    /**
     * dotenv 优先，然后系统环境变量。空字符串视为不存在。
     */
    private static String read(io.github.cdimascio.dotenv.Dotenv dotenv, String key) {
        String value = dotenv.get(key);
        if (value == null || value.isBlank()) value = System.getenv(key);
        return (value == null || value.isBlank()) ? null : value;
    }
}
