package com.xilidou.marvis.harness.http;

import okhttp3.Request;

/**
 * Bearer Token 认证：Authorization: Bearer xxx header。
 *
 * <p>用于公司代理（如 proxy 的 proxy.example.com:8787）或其他 Anthropic-compatible 提供商。
 *
 * <p>HTTP header 示例：
 * <pre>
 *   Authorization: Bearer REDACTED-TOKEN
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
