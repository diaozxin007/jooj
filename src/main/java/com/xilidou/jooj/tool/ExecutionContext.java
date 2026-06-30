package com.xilidou.jooj.tool;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工具执行上下文 —— 一等公民参数,显式描述"谁在调 / 在哪调 / 还有什么约束"。
 *
 * <h3>为什么不用 ThreadLocal</h3>
 *
 * <p>jooj 此前在多个地方依赖 thread / context 的隐含传递(s14 cron / s15 teammate /
 * s17 idle loop daemon thread)。如果再用 ThreadLocal 传 cwd:
 * <ul>
 *   <li>新工具实现者**不知道**有这层隐含依赖</li>
 *   <li>跨线程语义靠"开发时记得 set/clear"约定,容易 silent bug</li>
 *   <li>未来扩展 permissions / quota / sandbox / trace_id 都要新增 ThreadLocal,失控</li>
 * </ul>
 *
 * <p>改用显式参数:工具签名里看得见,扩展只需 record 加字段,所有现有工具默认 fallback,
 * 不会被遗忘。
 *
 * <h3>当前字段</h3>
 *
 * <p>3 字段够 s18 worktree 隔离 + 审计:
 * <ul>
 *   <li>{@code cwd} —— 工具的工作目录;null 表示用工具自身默认(user.dir / 全局 workdir)</li>
 *   <li>{@code agentName} —— 调用者身份({@code "lead"} / teammate name / {@code "subagent"})</li>
 *   <li>{@code worktreeName} —— 已绑定的 worktree 名(若有);跟 cwd 同时给,方便审计</li>
 * </ul>
 *
 * <h3>未来扩展(record 加字段即可)</h3>
 *
 * <ul>
 *   <li>{@code permissions} —— 工具白名单 / 黑名单(目前在 Subagent / Teammate 内部维护)</li>
 *   <li>{@code traceId} —— 跨工具调用的请求追踪 ID</li>
 *   <li>{@code timeoutMs} —— 单工具调用超时</li>
 *   <li>{@code sandbox} —— 沙盒模式(只读 / 限文件系统范围)</li>
 * </ul>
 *
 * <p>新字段加上后,旧调用点(传 ExecutionContext.lead())继续工作,
 * 工具按需读新字段,**没有 silent breakage**。
 */
public record ExecutionContext(
        Path cwd,
        String agentName,
        String worktreeName,
        String sessionId,
        DeliveryHint deliveryHint
) {

    /**
     * 投递目标的暗示 —— 仅 channel 入站路径(微信/Discord/...) 时由 InboundDispatcher 填,
     * 其他路径(CLI / Web / cron-default)留 null。
     *
     * <p>用途(s21 Demo 20):CronTool.doSchedule 拿到此 hint 后,把 (channel, peerId) freeze
     * 进 CronJob,**让 cron 数据自描述路由,不依赖 jooj 内存反查表**。Hermes 的 origin 同款。
     */
    public record DeliveryHint(String channel, String peerId) {}

    /** Lead 主路径默认 ctx —— 无 cwd 覆盖,用工具自身默认根目录;无 sessionId / deliveryHint。 */
    public static ExecutionContext lead() {
        return new ExecutionContext(null, "lead", null, null, null);
    }

    /** Lead 主路径,绑定到指定 session(s20 Demo 9)。 */
    public static ExecutionContext leadInSession(String sessionId) {
        return new ExecutionContext(null, "lead", null, sessionId, null);
    }

    /**
     * s21 Demo 20:Channel 入站路径 ctx —— 含 deliveryHint,让本 turn 内 CronTool 等
     * 能拿到 (channel, peerId) freeze 进自描述消息(cron job)。
     */
    public static ExecutionContext leadInChannel(String sessionId, String channel, String peerId) {
        return new ExecutionContext(null, "lead", null, sessionId, new DeliveryHint(channel, peerId));
    }

    /**
     * Teammate 路径但**没绑定 worktree** —— 工具仍用默认 cwd,但审计能看到调用者是谁。
     */
    public static ExecutionContext forTeammate(String teammateName) {
        return new ExecutionContext(null, teammateName, null, null, null);
    }

    /**
     * Teammate 路径**已绑定 worktree** —— 工具切换到 worktree 路径执行。
     */
    public static ExecutionContext inWorktree(String teammateName,
                                              String worktreeName,
                                              Path worktreePath) {
        return new ExecutionContext(worktreePath, teammateName, worktreeName, null, null);
    }

    /**
     * 工具用的便利方法:有 cwd 优先用 cwd,否则 fallback 到 {@code defaultCwd}。
     *
     * @param defaultCwd 工具自身的默认根(BashTool: user.dir / FileSystemTool: workdir)
     * @return 实际应该用的工作目录
     */
    public Path cwdOr(Path defaultCwd) {
        return cwd != null ? cwd : defaultCwd;
    }

    /** 返 user.dir 作 fallback 的便利重载。 */
    public Path cwdOrUserDir() {
        return cwdOr(Paths.get(System.getProperty("user.dir")));
    }
}
