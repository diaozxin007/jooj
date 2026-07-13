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
     * s22 D-11:工具执行前的一句话摘要,给前端 "正在执行……" loading 气泡用。
     *
     * <p>用户等 60s 期间要能看到 agent 在做什么,后台日志 + 转 tool_result 都太远。
     * agent loop 在 tool 循环里调本方法生成摘要,push 到 {@code TurnEventStream},前端 poll
     * {@code /api/chat/{sid}/events} 拿到并实时更新气泡。
     *
     * <p><b>约束</b>:
     * <ul>
     *   <li>**必须快**(纯字符串组装,不发起 IO、不访问 network、不查库)</li>
     *   <li>**不抛异常** —— 摘要失败降级到默认实现,不该影响 tool 执行</li>
     *   <li>建议 60 字以内,前端气泡区域有限</li>
     * </ul>
     *
     * <p><b>默认实现</b>:返回 {@code name(inputPreview)},适合无覆写的工具(todo / cron 等)。
     * 主要工具(bash / read_file / task / web_search)应该 override 出更友好的摘要
     * —— 比如 {@code "$ rm -rf build"} 而不是 {@code "bash({command=rm -rf build})"}。
     *
     * @param call 工具调用参数(name + arguments)
     * @return 一行摘要字符串,永不 null;失败时至少返 name
     */
    default String summary(ToolCall call) {
        if (call == null) return getName();
        String args = call.getArguments() == null ? "" : call.getArguments().toString();
        if (args.length() > 60) args = args.substring(0, 60) + "...";
        return call.getToolName() + args;
    }

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
