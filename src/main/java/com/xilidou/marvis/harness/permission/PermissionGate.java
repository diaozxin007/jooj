package com.xilidou.marvis.harness.permission;

import com.xilidou.marvis.harness.http.dto.ToolUseBlock;

/**
 * 权限闸门接口。
 *
 * <p>每个实现是一道闸门，对应 s03 的三道：
 * <ol>
 *   <li>{@link DenyListGate} - 硬黑名单（rm -rf /, sudo 等）</li>
 *   <li>{@link RuleBasedGate} - 规则匹配（写到 workspace 外、destructive 命令）</li>
 *   <li>{@link UserApprovalGate} - 用户阻塞确认</li>
 * </ol>
 *
 * <p>每个 Gate 独立可测、可组合。{@link PermissionPipeline} 负责串起来。
 */
public interface PermissionGate {

    /**
     * 检查一个 tool_use 是否被该闸门允许。
     *
     * @param toolUse LLM 想调用的工具
     * @return ALLOW / DENY / ASK 三态结果
     */
    PermissionResult check(ToolUseBlock toolUse);
}
