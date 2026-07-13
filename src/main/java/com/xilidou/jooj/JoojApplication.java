package com.xilidou.jooj;

import com.xilidou.jooj.bootstrap.JoojHome;
import com.xilidou.jooj.bootstrap.PidfileGuard;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Spring Boot 启动类(切片 C 后:真·主入口)。
 *
 * <p>切片 C 之前 jooj 主线代码不依赖 Spring 容器(走 {@code AgentLoopHarness.fromEnv()}
 * 手工装配)。切片 C 完成后:
 * <ul>
 *   <li>容器拉起所有 {@code @Component}(Tool/Hook/Service)</li>
 *   <li>{@link JoojCliRunner}({@code @Profile("!web & !test")})调用
 *       {@link com.xilidou.jooj.agent.AgentLoopHarness#repl()} 启动交互循环(CLI 模式)</li>
 *   <li>{@code web} profile 时不跑 CLI runner,改起 Tomcat 暴露
 *       {@link com.xilidou.jooj.web.ChatController} 的 REST 接口</li>
 * </ul>
 *
 * <h3>启动方式</h3>
 *
 * <pre>
 *   # CLI 模式(默认,跟之前一样)
 *   ./mvnw spring-boot:run
 *
 *   # Web 模式(--web 或 -Dspring.profiles.active=web)
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments=--web
 *   # 或
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=web
 * </pre>
 *
 * <p><b>关键参数</b>:
 * <ul>
 *   <li>{@link Banner.Mode#OFF} — 关闭 Spring 大旗,REPL 启动更干净</li>
 *   <li>web profile 启用时 web-application-type=SERVLET,默认 = NONE</li>
 * </ul>
 *
 * <p>{@link ConfigurationPropertiesScan} 让 Spring 找到 {@link JoojProperties}
 * 而不需要在每个配置类上重复 {@code @EnableConfigurationProperties}。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JoojApplication {

    public static void main(String[] args) {
        boolean webMode = isWebMode(args);

        // s22 P5 (D12):pidfile guard —— 阻断第二个 jooj 实例启动。
        // 事件驱动 transcript / 单进程假设的所有 IO 语义都建立在"只有一个 JVM 写 ~/.jooj/"上。
        // 检查放在容器起来前:若有活跃实例存在,直接抛异常 fail-fast,连 Spring 上下文都不装。
        try {
            Path home = JoojHome.getHomePath();
            JoojHome.ensureHome(home);
            PidfileGuard.acquire(home);
        } catch (IOException e) {
            System.err.println("[Bootstrap] failed to acquire pidfile: " + e.getMessage());
            System.exit(1);
        }

        SpringApplicationBuilder builder = new SpringApplicationBuilder(JoojApplication.class)
                .bannerMode(Banner.Mode.OFF);
        if (webMode) {
            builder.web(WebApplicationType.SERVLET).profiles("web");
        } else {
            builder.web(WebApplicationType.NONE);
        }
        builder.run(args);
    }

    /**
     * Web 模式判定 —— 任意一项命中即启用:
     * <ul>
     *   <li>命令行 {@code --web} 参数</li>
     *   <li>{@code spring.profiles.active} 系统属性 / 环境变量含 {@code web}</li>
     * </ul>
     *
     * <p>没有命中任何条件 → 走原 CLI 模式,跟切片 C 之前完全一致。
     */
    private static boolean isWebMode(String[] args) {
        if (args != null && Arrays.stream(args).anyMatch(a ->
                "--web".equals(a) || "-web".equals(a))) {
            return true;
        }
        String profiles = System.getProperty("spring.profiles.active",
                System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", ""));
        return profiles != null && profiles.toLowerCase().contains("web");
    }
}
