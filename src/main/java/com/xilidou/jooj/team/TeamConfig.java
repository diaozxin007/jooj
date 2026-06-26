package com.xilidou.jooj.team;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Team / MessageBus 配置 —— 跟 {@link com.xilidou.jooj.cron.CronConfig} 同形态。
 *
 * <p>纯 POJO 无 Spring 依赖,生产用 {@link TeamConfiguration} 从
 * {@link com.xilidou.jooj.JoojProperties.Team} 转出,测试用全参构造器。
 */
public class TeamConfig {

    private final Path mailboxDir;

    /** 默认值构造器(测试用):cwd/.mailboxes。 */
    public TeamConfig() {
        this(defaultMailboxDir());
    }

    /** 全参构造器(测试 / 自定义 / 生产)。 */
    public TeamConfig(Path mailboxDir) {
        if (mailboxDir == null) {
            throw new IllegalArgumentException("mailboxDir must not be null");
        }
        this.mailboxDir = mailboxDir;
    }

    private static Path defaultMailboxDir() {
        return Paths.get(System.getProperty("user.dir"), ".mailboxes");
    }

    /**
     * mailbox 根目录,默认 {@code <cwd>/.mailboxes/}。
     *
     * <p>每个 agent 一个 {@code <name>.jsonl} 文件,每行一条 JSON 消息。
     */
    public Path mailboxDir() {
        return mailboxDir;
    }
}
