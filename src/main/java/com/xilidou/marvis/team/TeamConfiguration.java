package com.xilidou.marvis.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.MarvisProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Team / MessageBus(s15)的 Spring 装配。
 *
 * <p>跟 {@link com.xilidou.marvis.cron.CronConfiguration} /
 * {@link com.xilidou.marvis.tasks.TasksConfiguration} 同模式 ——
 * model 层({@link TeamConfig} / {@link MessageBus})不自己 {@code @Component},
 * 测试可以 {@code new MessageBus(...)} 不依赖容器。
 */
@Configuration
public class TeamConfiguration {

    @Bean
    public TeamConfig teamConfig(MarvisProperties props) {
        Path mailboxDir = Paths.get(props.getTeam().getMailboxDir())
                .toAbsolutePath().normalize();
        return new TeamConfig(mailboxDir);
    }

    @Bean
    public MessageBus messageBus(TeamConfig config,
                                 @Qualifier("marvisObjectMapper") ObjectMapper json) {
        return new MessageBus(config, json);
    }

    /**
     * s18:Git CLI 客户端 —— 默认实现走 {@link ProcessBuilder} 调系统 git。
     *
     * <p>测试可以注入 mock {@link GitClient} 验业务逻辑而不真起 git 进程。
     */
    @Bean
    public GitClient gitClient() {
        return new GitClient.DefaultGitClient();
    }
}
