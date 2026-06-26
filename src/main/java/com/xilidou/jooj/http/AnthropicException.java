package com.xilidou.jooj.http;

/**
 * Anthropic API 调用异常。
 *
 * <p>包装 4xx/5xx HTTP 错误和 IO 错误，调用方可以根据 statusCode 判断是否重试。
 *
 * <p>常见错误码：
 * <ul>
 *   <li>400 - 请求体格式错误（最常见：messages 顺序、stop_reason 误用）</li>
 *   <li>401 - 认证失败（API Key 或 Bearer Token 错）</li>
 *   <li>429 - Rate Limit 超限（应重试 + 指数退避）</li>
 *   <li>500/502/503 - 服务端错误（应重试）</li>
 *   <li>0 - 我们用来表示 IO 错误（网络断开 / timeout）</li>
 * </ul>
 */
public class AnthropicException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public AnthropicException(int statusCode, String responseBody) {
        super(buildMessage(statusCode, responseBody));
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public AnthropicException(int statusCode, String responseBody, Throwable cause) {
        super(buildMessage(statusCode, responseBody), cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    /**
     * 是否值得重试（429 / 5xx）
     */
    public boolean isRetryable() {
        return statusCode == 0           // IO 错误
                || statusCode == 429      // Rate Limit
                || (statusCode >= 500 && statusCode < 600);
    }

    /**
     * 是否是 "prompt too long" 错误(messages 总 token 超过 context window)。
     *
     * <p>Anthropic API 在 messages 超长时返回 400, body 类似:
     * <pre>
     *   {"type":"error","error":{"type":"invalid_request_error",
     *    "message":"prompt is too long: 250000 tokens > 200000 maximum"}}
     * </pre>
     *
     * <p>判定:statusCode=400 且 body 包含 "prompt is too long" 或 "prompt_too_long"。
     * 这是 L4 reactive_compact 的触发信号。
     */
    public boolean isPromptTooLong() {
        if (statusCode != 400 || responseBody == null) return false;
        String body = responseBody.toLowerCase();
        return body.contains("prompt is too long") || body.contains("prompt_too_long");
    }

    private static String buildMessage(int statusCode, String responseBody) {
        if (statusCode == 0) {
            return "Anthropic API IO error: " + responseBody;
        }
        return "Anthropic API error " + statusCode + ": " + responseBody;
    }
}
