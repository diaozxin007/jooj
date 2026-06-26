package com.xilidou.jooj.http;

import okhttp3.Request;

/**
 * Anthropic 官方认证：x-api-key header。
 *
 * <p>申请：https://console.anthropic.com/
 *
 * <p>JSON 示例（不在 JSON 里，是 HTTP header）：
 * <pre>
 *   x-api-key: sk-ant-api03-xxxxx
 * </pre>
 */
public final class ApiKeyAuth implements HttpAuth {

    private final String apiKey;

    public ApiKeyAuth(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        this.apiKey = apiKey;
    }

    @Override
    public void apply(Request.Builder requestBuilder) {
        requestBuilder.header("x-api-key", apiKey);
    }

    @Override
    public String describe() {
        return "x-api-key (Anthropic 官方)";
    }
}
