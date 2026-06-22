package com.xilidou.marvis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 启动类（占位）。
 *
 * <p>当前 marvis 主线代码不依赖 Spring 容器：
 * <ul>
 *   <li>{@link com.xilidou.marvis.S01} 通过 main + 构造器注入直接跑</li>
 *   <li>{@link com.xilidou.marvis.harness.agent.AgentLoopHarness#fromEnv} 装配所有依赖</li>
 * </ul>
 *
 * <p>保留这个类是为了：
 * <ul>
 *   <li>Week 12 部署时可以加 Web UI（{@code @RestController}）</li>
 *   <li>Week 8 整合 Mini Harness 时统一切换到 IoC 容器</li>
 * </ul>
 *
 * <p>历史：原 main 演示了 Day 3 风格的 AgentHarness（mock LLM + ToolRegistry），
 * 已迁移到 {@code harness/archive/day3/AgentHarness}。
 */
@SpringBootApplication
public class MarvisApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarvisApplication.class, args);
    }

}
