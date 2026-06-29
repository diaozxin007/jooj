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
        String sessionId
) {

    /** Lead 主路径默认 ctx —— 无 cwd 覆盖,用工具自身默认根目录;无 sessionId(老调用点兼容)。 */
    public static ExecutionContext lead() {
        return new ExecutionContext(null, "lead", null, null);
    }

    /** Lead 主路径,绑定到指定 session(s20 Demo 9:cron 等"工具侧记 session"场景)。 */
    public static ExecutionContext leadInSession(String sessionId) {
        return new ExecutionContext(null, "lead", null, sessionId);
    }

    /**
     * Teammate 路径但**没绑定 worktree** —— 工具仍用默认 cwd,但审计能看到调用者是谁。
     */
    public static ExecutionContext forTeammate(String teammateName) {
        return new ExecutionContext(null, teammateName, null, null);
    }

    /**
     * Teammate 路径**已绑定 worktree** —— 工具切换到 worktree 路径执行。
     *
     * @param teammateName 队友 name
     * @param worktreeName 关联的 worktree 名(纯 string 标识,审计用)
     * @param worktreePath worktree 在文件系统上的路径
     */
    public static ExecutionContext inWorktree(String teammateName,
                                              String worktreeName,
                                              Path worktreePath) {
        return new ExecutionContext(worktreePath, teammateName, worktreeName, null);
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
