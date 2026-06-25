package com.xilidou.marvis.http;

import okhttp3.Request;

/**
 * Anthropic API 认证策略。
 *
 * <p>支持两种认证方式(实现类二选一):
 * <ul>
 *   <li>{@link ApiKeyAuth} - 官方 Anthropic,header: {@code x-api-key}</li>
 *   <li>{@link BearerTokenAuth} - 反向代理 / 第三方兼容服务,header: {@code Authorization: Bearer}</li>
 * </ul>
 *
 * <p>用 Strategy Pattern 是为了:
 * <ol>
 *   <li>类型安全:编译期就确定用哪种 auth,避免运行时 if-else</li>
 *   <li>可扩展:将来加 OAuth / mTLS 只需新增实现类</li>
 *   <li>可测试:测试时可以注入 NoOpAuth 跳过认证</li>
 * </ol>
 *
 * <p>切片 C 之后:fromEnv 工厂已移除,实例化逻辑搬到
 * {@link com.xilidou.marvis.http.HttpClientConfig#httpAuth} Bean 里。
 *
 * <p>典型用法:
 * <pre>
 *   // Spring 场景
 *   @Autowired HttpAuth auth;
 *
 *   // 测试场景
 *   HttpAuth auth = new ApiKeyAuth("test-key");
 *   Request.Builder req = new Request.Builder().url(...);
 *   auth.apply(req);
 * </pre>
 */
public interface HttpAuth {

    /**
     * 把认证 header 应用到请求 Builder 上。
     *
     * @param requestBuilder OkHttp 请求 Builder(mutable)
     */
    void apply(Request.Builder requestBuilder);

    /**
     * 返回此 auth 的描述(用于日志,不要包含敏感信息)
     */
    String describe();
}
