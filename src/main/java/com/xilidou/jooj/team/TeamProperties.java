package com.xilidou.jooj.team;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Team / MessageBus(s15+)的 yml → Java 桥接。
 *
 * <p>三分法(参见 [[Jooj项目_配置架构重构_规划]] D-05):
 * <ul>
 *   <li>{@link TeamProperties}(本类)—— {@code @ConfigurationProperties("jooj.team")}</li>
 *   <li>{@link TeamConfig} —— 运行时 POJO,{@code mailboxDir} 已解析为绝对 Path</li>
 *   <li>{@link TeamConfiguration} —— {@code @Bean} 装配</li>
 * </ul>
 *
 * <p>字段来自 3 章节:
 * <ul>
 *   <li>s15 —— {@link #mailboxDir}(文件邮箱形态的 agent 间通信)</li>
 *   <li>s17 —— {@link #idlePollMs} / {@link #idleTimeoutMs}(Teammate IDLE 阶段轮询)</li>
 *   <li>s18 —— {@link #worktreeDir}(git worktree 隔离)</li>
 * </ul>
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.Team} 拆出,前缀 {@code jooj.team} 保持不变。
 */
@Data
@ConfigurationProperties("jooj.team")
public class TeamProperties {

    /** mailbox 目录(相对 cwd 或绝对路径)。默认 {@code .mailboxes}。 */
    private String mailboxDir = ".mailboxes";

    /**
     * IDLE 阶段轮询间隔(毫秒)。默认 5000(对齐上游 s17 IDLE_POLL_INTERVAL=5)。
     * 测试 profile 可调小到 50ms 让测试跑得快。
     */
    private long idlePollMs = 5000;

    /**
     * IDLE 阶段总超时(毫秒)—— 累计这么久没活就退出 teammate。
     * 默认 60000(对齐上游 s17 IDLE_TIMEOUT=60)。测试可调小到 200ms。
     */
    private long idleTimeoutMs = 60_000;

    /**
     * s18 worktree 根目录(相对 jooj workdir 或绝对路径)。默认 {@code .worktrees}。
     * 跟上游 s18 {@code WORKTREES_DIR = WORKDIR / ".worktrees"} 一致。
     */
    private String worktreeDir = ".worktrees";
}
