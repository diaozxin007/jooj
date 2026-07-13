package com.xilidou.jooj.permission;

import com.xilidou.jooj.agent.AgentControl;
import com.xilidou.jooj.agent.AgentInterruptedException;
import com.xilidou.jooj.agent.SessionContext;
import com.xilidou.jooj.agent.control.Answer;
import com.xilidou.jooj.agent.control.AskTimeoutException;
import com.xilidou.jooj.agent.control.PermissionQuestion;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * s22 D-10-C:web 场景的 {@link UserApprover} 实现 —— 通过 {@link AgentControl} 把
 * ASK 冒泡到 REST 前端,阻塞等 answer。
 *
 * <h3>与 CLI approver 的区别</h3>
 *
 * <ul>
 *   <li>{@link ConsoleUserApprover} 阻塞在 {@code Scanner.hasNextLine()} 读 stdin —— 只能 CLI 用</li>
 *   <li>{@link WebUserApprover}:调 {@code agentControl.ask(sid, question, 3min)},阻塞在
 *       CompletableFuture 上,REST layer 的 {@code POST /answer} 唤醒</li>
 * </ul>
 *
 * <h3>sid 从哪来</h3>
 *
 * <p>{@link SessionContext#current()} —— agentLoop 进入 turn 时 push,退出 pop
 * (D-10-C step 1)。ASK 触发点(PermissionHook → PermissionPipeline → UserApprovalGate
 * → UserApprover.approve)是 lead 线程栈的 6+ 层深处,不可能改所有中间层加 sid 参数。
 * ThreadLocal 是最简洁的解法(参考 Hermes {@code contextvars.ContextVar})。
 *
 * <h3>降级路径</h3>
 *
 * <p>{@link SessionContext#isBound()} 返 false 时(比如 CLI 测试直接调 pipeline)
 * 走"保守 deny" —— 无 sid 意味着无 web 通道,不能真让危险命令通过。
 * 生产 web 路径永远有 sid,这个 branch 只在测试或误用时命中。
 *
 * <h3>为什么不直接注 CLI approver 也能 web</h3>
 *
 * <p>用户已选 Sem A(WebUserApprover 独立类,CLI approver 保持不动)。
 * 用 {@code permission.approver=web|cli|always-allow|always-deny} 配置切换,
 * 见 {@link PermissionConfiguration}。
 */
@Slf4j
public class WebUserApprover implements UserApprover {

    /** 默认阻塞超时 —— 匹配 Hermes {@code approvals.timeout_s} 60s ~ 300s 常见值。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(3);

    private final AgentControl agentControl;
    private final Duration timeout;

    public WebUserApprover(AgentControl agentControl) {
        this(agentControl, DEFAULT_TIMEOUT);
    }

    public WebUserApprover(AgentControl agentControl, Duration timeout) {
        this.agentControl = agentControl;
        this.timeout = timeout;
    }

    @Override
    public boolean approve(ToolUseBlock toolUse, String reason) {
        String sid = SessionContext.current();
        if (sid == null || sid.isBlank()) {
            // 降级:无 sid 意味着调用点不在 agent turn 里(测试 / 误用)。保守 deny。
            log.warn("[WebApprover] no sid in context — defaulting to DENY for tool={}",
                    toolUse.getName());
            return false;
        }

        PermissionQuestion question = PermissionQuestion.of(toolUse, reason);
        log.info("[WebApprover] escalating tool={} to web (sid={} askId={})",
                toolUse.getName(), sid, question.askId());

        try {
            Answer answer = agentControl.ask(sid, question, timeout);
            boolean allowed = answer.isAllow();
            log.info("[WebApprover] tool={} sid={} askId={} decision={}",
                    toolUse.getName(), sid, question.askId(), allowed ? "ALLOW" : "DENY");
            return allowed;
        } catch (AskTimeoutException ate) {
            // 超时 → 保守 DENY(user did not respond)。日志已在 AgentControl 侧打过。
            log.warn("[WebApprover] tool={} sid={} askId={} timed out — denying",
                    toolUse.getName(), sid, ate.getAskId());
            return false;
        } catch (AgentInterruptedException aie) {
            // 用户在挂起期间点了 stop → DENY tool + let interrupt propagate?
            // 决策:tool 侧 DENY,让 loop 继续走到下一个 while 顶部检查点由 loop 自己抛
            // AgentInterruptedException(D-10-B step 4 已实现)。
            // 这里 return false 即可,不重抛 —— agentLoop 那层会消费 flag。
            log.info("[WebApprover] tool={} sid={} interrupted during ask — denying",
                    toolUse.getName(), sid);
            return false;
        } catch (InterruptedException ie) {
            // JVM 层线程中断 (Thread.interrupt),不是用户 signal。恢复标志并 DENY。
            Thread.currentThread().interrupt();
            log.warn("[WebApprover] thread interrupted during ask — denying");
            return false;
        }
    }
}
