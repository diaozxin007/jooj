package com.xilidou.jooj.agent.control;

import com.xilidou.jooj.http.dto.ToolUseBlock;

import java.time.Instant;

/**
 * s22 D-10-B:请求用户批准工具调用的 pending question。
 *
 * <p>由 {@code PermissionHook}(D-10-C 会改)在 pre-tool-use 阶段判 ASK 决策时构造,
 * 通过 {@code AgentControl.ask} 挂起 loop 等答复。
 *
 * <p>字段:
 * <ul>
 *   <li>{@code toolName} —— 工具名,前端显示"允许调用 bash 吗?"</li>
 *   <li>{@code toolInput} —— 参数 JSON 字符串,前端可展示"命令是 rm -rf build/"</li>
 *   <li>{@code reason} —— 为什么触发 ASK(来自 Rule Gate 匹配)</li>
 * </ul>
 *
 * <p><b>不存整个 ToolUseBlock</b>:ToolUseBlock 含 tool_use_id 是 LLM 侧概念,
 * 泄露给前端没意义。只暴露前端渲染需要的最小字段。
 *
 * @param askId     唯一 ID,前端用来匹配 answer
 * @param askedAt   挂起时间戳
 * @param toolName  工具名(如 "bash"、"write_file")
 * @param toolInput 工具输入的 JSON 字符串
 * @param reason    为什么需要用户批准(Rule Gate 给的原因)
 */
public record PermissionQuestion(
        String askId,
        Instant askedAt,
        String toolName,
        String toolInput,
        String reason
) implements PendingQuestion {

    @Override
    public String type() {
        return "permission";
    }

    /** 便利工厂:从 ToolUseBlock + reason 构造。 */
    public static PermissionQuestion of(ToolUseBlock toolUse, String reason) {
        String input = toolUse.getInput() != null ? toolUse.getInput().toString() : "";
        return new PermissionQuestion(
                PendingQuestion.newAskId(),
                Instant.now(),
                toolUse.getName(),
                input,
                reason);
    }
}
