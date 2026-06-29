package com.xilidou.jooj.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.bootstrap.JoojHome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Session(多对话并存)的 Spring 装配。
 *
 * <p>跟 {@link com.xilidou.jooj.tasks.TasksConfiguration} /
 * {@link com.xilidou.jooj.memory.MemoryConfiguration} 同模式 ——
 * model 层(SessionStore / SessionService)不自己 {@code @Component},
 * 测试可以 {@code new ...} 不依赖容器。
 *
 * <h3>启动期 ensure</h3>
 *
 * <p>{@code SessionService} 通过 {@code @Bean(initMethod = "ensureBootstrap")}
 * 在容器装配完成后自动跑一次,确保 {@code default} / {@code cli-default} /
 * {@code cron-default} 三个特殊 session 存在。
 *
 * <h3>{@link AgentLockProvider} 的引入</h3>
 *
 * <p>原来 {@code agentLock} 是单一全局锁(REPL + cron 共享一把);引入 session 后语义变了:
 * 不同 session 应该并行,同 session 互斥。
 * 旧的 {@code agentLock} bean 仍保留,给迁移期向后兼容用 —— 真正多 session 的场景
 * 走 {@link AgentLockProvider#lockFor(String)}。
 */
@Configuration
@Slf4j
public class SessionConfiguration {

    @Bean
    public SessionStore sessionStore(@Qualifier("joojObjectMapper") ObjectMapper json) throws IOException {
        Path home = JoojHome.getHomePath();
        JoojHome.ensureHome(home);
        Path sessionsDir = JoojHome.ensureSubdir(home, "sessions");
        log.info("[Session] sessions dir: {}", sessionsDir);
        return new SessionStore(sessionsDir, json);
    }

    /**
     * SessionService bean。{@code initMethod} 让 Spring 在依赖装配完成后立刻调
     * {@link SessionService#ensureBootstrap()},三个 reserved session 自动建立。
     */
    @Bean(initMethod = "ensureBootstrap")
    public SessionService sessionService(SessionStore store) {
        return new SessionService(store);
    }

    @Bean
    public AgentLockProvider agentLockProvider() {
        return new AgentLockProvider();
    }
}
