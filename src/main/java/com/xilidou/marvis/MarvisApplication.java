package com.xilidou.marvis;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot 启动类(切片 C 后:真·主入口)。
 *
 * <p>切片 C 之前 marvis 主线代码不依赖 Spring 容器(走 {@code AgentLoopHarness.fromEnv()}
 * 手工装配)。切片 C 完成后:
 * <ul>
 *   <li>容器拉起所有 {@code @Component}(Tool/Hook/Service)</li>
 *   <li>{@link MarvisCliRunner}({@code @Profile("!test")})调用
 *       {@link com.xilidou.marvis.agent.AgentLoopHarness#repl()} 启动交互循环</li>
 * </ul>
 *
 * <p><b>关键参数</b>:
 * <ul>
 *   <li>{@link WebApplicationType#NONE} — 显式声明 CLI(不起 web,不需要 spring-boot-starter-web)</li>
 *   <li>{@link Banner.Mode#OFF} — 关闭 Spring 大旗,REPL 启动更干净</li>
 * </ul>
 *
 * <p>{@link ConfigurationPropertiesScan} 让 Spring 找到 {@link MarvisProperties}
 * 而不需要在每个配置类上重复 {@code @EnableConfigurationProperties}。
 *
 * <p><b>为什么 REPL 不直接写在本类的 CommandLineRunner 上</b>:
 * 测试场景下 @SpringBootTest 也会触发 CommandLineRunner,REPL 会卡在等 stdin。
 * 提取出 {@link MarvisCliRunner} 加 {@code @Profile("!test")} 才能让所有
 * @SpringBootTest 装配完毕直接退出。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MarvisApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(MarvisApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(args);
    }
}
