package com.xilidou.jooj.tool;

import java.util.List;

/**
 * 工具接口 —— jooj 的工具协议。
 *
 * <p>s18 起加 {@link ExecutionContext} 作一等公民参数,描述"谁在调 / 在哪调"。
 * 通过 {@code default} 方法兼容旧实现:
 *
 * <ul>
 *   <li>不感知 ctx 的工具(todo / cron / team / tasks / task / skill)
 *       继续实现旧 {@link #execute(ToolCall)},新签名的 default 转发到这里</li>
 *   <li>需要 cwd / 审计 / 沙盒等 ctx 信息的工具(bash / filesystem)重写
 *       {@link #execute(ToolCall, ExecutionContext)}</li>
 * </ul>
 *
 * <p>调用方(ToolRegistry / AgentLoopHarness / Subagent / Teammate)总是调
 * {@link #execute(ToolCall, ExecutionContext)} 显式传 ctx —— 这样不依赖任何
 * 隐含上下文(无 ThreadLocal),工程化可靠。
 *
 * <p><b>为什么不用 ThreadLocal</b>:ThreadLocal 是隐含依赖,新工具实现者不知道有它;
 * 跨线程语义(teammate / bg / cron daemon)靠"开发时记得 set/clear"约定,容易 silent bug;
 * 未来扩展 permissions / quota / sandbox 都要新增 ThreadLocal,失控。
 * 显式参数是工程化方向。
 */
public interface Tool {

    String getName();

    String getDescription();

    List<ToolDefinition> getTools();

    /**
     * 旧签名 —— 不感知 ExecutionContext。
     *
     * <p>所有现有工具(s01-s17)实现这个签名。s18 起新增 {@link #execute(ToolCall, ExecutionContext)}
     * 默认转发到这里,因此**不需要修改现有工具**。
     */
    ToolResult execute(ToolCall call);

    /**
     * s18 新签名 —— 工具感知 {@link ExecutionContext}。
     *
     * <p>默认实现:转发到不带 ctx 的旧签名 —— 现有 7 个工具不需要改动。
     * 需要 cwd / 审计 / 等的工具重写本方法。
     *
     * @param call 工具调用参数
     * @param ctx  执行上下文(永不为 null;Lead 默认走 {@link ExecutionContext#lead()})
     * @return 工具执行结果
     */
    default ToolResult execute(ToolCall call, ExecutionContext ctx) {
        return execute(call);
    }
}
