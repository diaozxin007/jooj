package com.xilidou.marvis.permission;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 权限检查结果：决策 + 原因。
 *
 * <p>原因（{@code reason}）会出现在两个地方：
 * <ul>
 *   <li>用户审批界面（"⚠ {reason}"）</li>
 *   <li>tool_result 回传给 LLM（让 LLM 知道为什么被拒）</li>
 * </ul>
 *
 * <p>常用静态工厂：
 * <pre>
 *   PermissionResult.allow();
 *   PermissionResult.deny("rm -rf / 被禁止");
 *   PermissionResult.ask("写文件到 workspace 外");
 * </pre>
 */
@Data
@AllArgsConstructor
public class PermissionResult {

    private final PermissionDecision decision;

    /** 决策原因（DENY/ASK 时必填，ALLOW 时可空） */
    private final String reason;

    public static PermissionResult allow() {
        return new PermissionResult(PermissionDecision.ALLOW, null);
    }

    public static PermissionResult deny(String reason) {
        return new PermissionResult(PermissionDecision.DENY, reason);
    }

    public static PermissionResult ask(String reason) {
        return new PermissionResult(PermissionDecision.ASK, reason);
    }

    public boolean isAllow() {
        return decision == PermissionDecision.ALLOW;
    }

    public boolean isDeny() {
        return decision == PermissionDecision.DENY;
    }

    public boolean isAsk() {
        return decision == PermissionDecision.ASK;
    }
}
