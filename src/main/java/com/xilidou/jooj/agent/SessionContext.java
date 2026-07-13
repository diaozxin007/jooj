package com.xilidou.jooj.agent;

import lombok.extern.slf4j.Slf4j;

/**
 * s22 D-10-C:ThreadLocal 载着当前 agent turn 的 sessionId,给深层调用点(Hook、
 * UserApprover 等)使用,避免在多个 API 层显式传参。
 *
 * <h3>为什么用 ThreadLocal</h3>
 *
 * <p>Hermes 用 Python 的 {@code contextvars.ContextVar} 做同一件事(见
 * {@code tools/approval.py:171 set_current_session_key}):agent 线程栈深层的工具在
 * pre_tool_call hook 里 escalate 到人类审批时,需要拿当前 session 的 approval 队列。
 *
 * <p>Java 里对等设施是 {@link ThreadLocal}(不用 InheritableThreadLocal —— jooj lead
 * 线程模型是单线程 turn,subagent 也在同一线程 spawn 而不是子线程,不需要继承)。
 *
 * <h3>使用协议</h3>
 *
 * <p>{@link AgentLoopHarness#processOneQuery} 和 {@link com.xilidou.jooj.subagent.Subagent#spawn}
 * 在进入 turn 之前调 {@link #push(String)},退出时(try/finally)调 {@link #pop()}。
 * Hook / Approver 内部调 {@link #current()}。
 *
 * <p><b>push / pop 必须成对</b>,否则 ThreadLocal 泄露到下一次同线程的调用(线程池场景致命)。
 * caller 用 try/finally 保护:
 * <pre>
 *   String prev = SessionContext.push(sid);
 *   try {
 *       // ... work
 *   } finally {
 *       SessionContext.pop(prev);
 *   }
 * </pre>
 *
 * <p>{@link #current()} 无 sid 时返 null —— 调用方决定是"降级(CLI 模式)"还是"抛异常"。
 * 目前 WebUserApprover 走"降级到 CLI stdin"路径。
 *
 * <h3>为什么不叫 ExecutionContext</h3>
 *
 * <p>jooj 已有 {@code com.xilidou.jooj.tool.ExecutionContext} record,是**显式**传给 Tool.execute
 * 的参数。SessionContext 是**隐式** ThreadLocal,专为无法改契约的层(Hook)提供 sid。
 * 两者共存,分工不同。
 */
@Slf4j
public final class SessionContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private SessionContext() {}

    /**
     * 将 sessionId 绑到当前线程。返回**之前**的 sid(可能 null),caller 必须在
     * finally 里调 {@link #pop(String)} 恢复,防止 ThreadLocal 泄漏。
     *
     * <p>返回旧值而非固定 pop() 无参:支持嵌套(subagent 在 lead 线程 spawn 时嵌套 push
     * 同一 sid,pop 时精确恢复到 lead 值)。
     */
    public static String push(String sessionId) {
        String previous = CURRENT.get();
        CURRENT.set(sessionId);
        return previous;
    }

    /**
     * 恢复到之前的 sid(通常是 null 或外层 sid)。传入 {@link #push} 的返回值。
     * previous == null 时 remove 以避免 ThreadLocal Map 里留空 entry。
     */
    public static void pop(String previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /**
     * 读当前线程绑定的 sessionId,可能 null(线程从未 push,或 push/pop 严格匹配后清空)。
     */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * 便利:current 非 null 且非 blank 时返 true(WebUserApprover 用来判断"走 web 还是降级")。
     */
    public static boolean isBound() {
        String s = CURRENT.get();
        return s != null && !s.isBlank();
    }
}
