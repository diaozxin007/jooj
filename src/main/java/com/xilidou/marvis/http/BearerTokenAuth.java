package com.xilidou.marvis.http;

import okhttp3.Request;

/**
 * Bearer Token 认证：Authorization: Bearer xxx header。
 *
 * <p>用于 Anthropic-compatible 反向代理（自建 / 第三方提供商均可）。
 *
 * <p>HTTP header 示例：
 * <pre>
 *   Authorization: Bearer &lt;your-token-here&gt;
 * </pre>
 */
public final class BearerTokenAuth implements HttpAuth {

    private final String token;

    public BearerTokenAuth(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        this.token = token;
    }

    @Override
    public void apply(Request.Builder requestBuilder) {
        requestBuilder.header("Authorization", "Bearer " + token);
    }

    @Override
    public String describe() {
        return "Bearer (proxy)";
    }
}
