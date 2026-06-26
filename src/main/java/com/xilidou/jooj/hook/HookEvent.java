package com.xilidou.jooj.hook;

/**
 * Hook 触发事件。对应 Python s04 的 4 个事件名：
 * <pre>
 *   HOOKS = {"UserPromptSubmit": [], "PreToolUse": [], "PostToolUse": [], "Stop": []}
 * </pre>
 *
 * <p>用 enum 而不是字符串，避免 typo（Python 的 {@code "PreToolUse"} 写成 {@code "PreToolUSe"}
 * 不会报错，Java 的 {@code HookEvent.PRE_TOOL_USE} 写错直接编译失败）。
 *
 * <p>每个事件的 hook 函数签名不同——见 {@code hook/} 包下的 4 个接口。
 */
public enum HookEvent {

    /**
     * 用户输入提交后，发给 LLM 之前。
     * <p>典型用途：日志、敏感信息检查、动态注入 context。
     * <p>参数：{@code String query}
     */
    USER_PROMPT_SUBMIT,

    /**
     * 工具执行前。
     * <p>典型用途：权限检查（s03 → s04 的 permission_hook）、日志、Metric 记录。
     * <p>参数：{@code ToolUseBlock toolUse}
     */
    PRE_TOOL_USE,

    /**
     * 工具执行后。
     * <p>典型用途：大输出告警、结果脱敏、缓存。
     * <p>参数：{@code ToolUseBlock toolUse, String output}
     */
    POST_TOOL_USE,

    /**
     * Loop 退出前。
     * <p>典型用途：会话总结、Metric 汇总、强制再问一轮（force_continue）。
     * <p>参数：{@code List<MessageParam> messages}
     */
    STOP
}
