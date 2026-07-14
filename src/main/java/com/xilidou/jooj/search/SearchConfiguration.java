package com.xilidou.jooj.search;

import com.xilidou.jooj.bootstrap.JoojHome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Search 子系统的 Spring 装配(s21 Demo 25)。
 *
 * <p>提供 3 个 Bean:
 * <ul>
 *   <li>{@link SearchConfig} —— 从 {@link SearchProperties} 转出</li>
 *   <li>{@link SearchStore} —— 单连接 + WAL,容器关时自动 close</li>
 *   <li>{@link SearchService} —— SessionService 钩子 + Tool 入口</li>
 * </ul>
 *
 * <p><b>为什么 SearchStore / SearchService 不自己 @Component</b>:同
 * {@link com.xilidou.jooj.memory.MemoryConfiguration} / {@link com.xilidou.jooj.session.SessionConfiguration}
 * 的理由 —— 测试可以 {@code new ...} 不依赖容器。
 *
 * <p><b>destroyMethod = "close"</b>:让 Spring 容器关时自动调 {@link SearchStore#close} 关连接。
 */
@Configuration
@Slf4j
public class SearchConfiguration {

    /** 把 {@link SearchProperties} 转成 {@link SearchConfig} —— db 路径落 jooj home。 */
    @Bean
    public SearchConfig searchConfig(SearchProperties s) throws IOException {
        Path home = JoojHome.getHomePath();
        JoojHome.ensureHome(home);
        Path dbPath = home.resolve(s.getDbFilename()).toAbsolutePath().normalize();
        log.info("[Search] db path: {}", dbPath);
        return new SearchConfig(
                dbPath,
                s.getSchemaVersion(),
                s.getDefaultLimit(),
                s.getMaxLimit(),
                s.getBusyTimeoutMs(),
                s.getStartupCheck()
        );
    }

    /**
     * SearchStore Bean。{@code destroyMethod = "close"} 让 Spring 容器关时关 SQLite 连接。
     * 测试中直接 {@code new SearchStore(config)},绕开容器。
     */
    @Bean(destroyMethod = "close")
    public SearchStore searchStore(SearchConfig config) {
        return new SearchStore(config);
    }

    @Bean
    public SearchService searchService(SearchStore store, SearchConfig config) {
        return new SearchService(store, config);
    }
}
