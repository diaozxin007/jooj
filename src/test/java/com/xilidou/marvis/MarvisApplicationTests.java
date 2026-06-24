package com.xilidou.marvis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 验证整个 Spring 容器能正常装配 —— 切片 C 端到端的"地基"测试。
 *
 * <p>用 {@link ActiveProfiles}("test") 加载 {@code application-test.yml},
 * 里面的 fake api-key + test-model 让 {@link MarvisProperties} / {@link com.xilidou.marvis.http.HttpClientConfig}
 * 装配通过,不依赖真实环境变量。
 *
 * <p>这个测试不通过即说明:Spring 化某个 Bean 的依赖图不闭合,需要先修才能继续。
 */
@SpringBootTest
@ActiveProfiles("test")
class MarvisApplicationTests {

    @Test
    void contextLoads() {
    }

}
