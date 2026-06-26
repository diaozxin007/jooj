package com.xilidou.jooj.permission;

import com.xilidou.jooj.http.dto.ToolUseBlock;

import java.util.Objects;

/**
 * Gate 3：用 {@link UserApprover} 把 ASK 升级为 ALLOW/DENY。
 *
 * <p>本身不产生 ASK——它**消费**前面 Gate 的 ASK。
 *
 * <p>用法：通常不直接用，而是被 {@link PermissionPipeline} 在前两个 Gate
 * 返回 ASK 时自动调用：
 * <pre>
 *   if (前一个 Gate 返回 ASK) {
 *       userApprover.approve(...) ? ALLOW : DENY
 *   }
 * </pre>
 *
 * <p>注意：本 Gate 单独跑会让所有工具都过（因为它接收已经是 ASK 的，本身不判规则）。
 * 它**只在 Pipeline 里有意义**。
 */
public class UserApprovalGate implements PermissionGate {

    private final UserApprover approver;

    public UserApprovalGate(UserApprover approver) {
        this.approver = Objects.requireNonNull(approver, "approver");
    }

    /**
     * 这个方法签名要求传 {@link ToolUseBlock}（PermissionGate 接口约定），
     * 但 Gate 3 真正需要的是上一个 Gate 的 ASK 结果（含 reason）。
     * 所以这个方法对外**不直接 useful**，调用方应该用 {@link #askWithReason}。
     */
    @Override
    public PermissionResult check(ToolUseBlock toolUse) {
        // 没有 reason 上下文，调用方应该用 askWithReason
        return approver.approve(toolUse, "(no reason provided)")
                ? PermissionResult.allow()
                : PermissionResult.deny("User denied");
    }

    /**
     * Pipeline 真正用的方法：基于上一个 Gate 的 reason 去问用户。
     */
    public PermissionResult askWithReason(ToolUseBlock toolUse, String reason) {
        return approver.approve(toolUse, reason)
                ? PermissionResult.allow()
                : PermissionResult.deny("User denied: " + reason);
    }
}
