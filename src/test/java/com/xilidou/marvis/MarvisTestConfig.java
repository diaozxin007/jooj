package com.xilidou.marvis;

import com.xilidou.marvis.http.AnthropicClient;
import com.xilidou.marvis.http.MockAnthropicClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 共享测试配置 —— 用 {@link MockAnthropicClient} 替换真实 {@link AnthropicClient},
 * 避免任何测试真发 HTTP。
 *
 * <p>用法:
 * <pre>
 *   @SpringBootTest
 *   @ActiveProfiles("test")
 *   @Import(MarvisTestConfig.class)
 *   class XxxTest {
 *       @Autowired MockAnthropicClient mock;  // 直接拿 mock(@Primary 让它优先)
 *
 *       @BeforeEach void setup() {
 *           mock.reset(ResponseFixtures.endTurn("done"));
 *       }
 *   }
 * </pre>
 *
 * <p><b>关键设计 — bean 名字不同 + @Primary</b>:
 * 这里 bean 名字是 {@code mockAnthropicClient}(与 {@link com.xilidou.marvis.http.HttpClientConfig#anthropicClient}
 * 不同),所以不发生 bean override。两个 bean 同时存在 BeanFactory 里,但 {@link Primary}
 * 让所有 by-type 注入(包括 {@link com.xilidou.marvis.agent.AgentLoopHarness}
 * 等的 {@code AnthropicClient} 参数)都解析到 mock 上,真实的 client 被绕过。
 *
 * <p>这种模式比 {@code allow-bean-definition-overriding} + 同名替换更稳:
 * <ul>
 *   <li>不依赖 @Configuration 加载顺序</li>
 *   <li>不需要在 application.yml 打开全局 override(Spring Boot 4 默认禁止有理由)</li>
 *   <li>测试可以同时按类型注入 {@code MockAnthropicClient}(直接拿到具体类型)
 *       或按类型注入 {@code AnthropicClient}(被 @Primary 解析到同一个 mock)</li>
 * </ul>
 */
@TestConfiguration
public class MarvisTestConfig {

    /**
     * Mock {@link AnthropicClient} —— 默认 responder 抛 IllegalStateException
     * (没设置 fixture 就调,说明测试漏了 setup)。
     *
     * <p>返回类型显式声明为 {@link MockAnthropicClient} 而非基类,让测试可以
     * {@code @Autowired MockAnthropicClient mock} 直接拿到具体类型,无需 cast。
     */
    @Bean
    @Primary
    public MockAnthropicClient mockAnthropicClient() {
        return new MockAnthropicClient(req -> {
            throw new IllegalStateException(
                    "MockAnthropicClient called without fixture configured; " +
                            "set up via mock.reset(...) in test @BeforeEach");
        });
    }
}
