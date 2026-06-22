package com.xilidou.marvis.harness.permission;

/**
 * 权限决策三态。
 *
 * <p>对应 s03 三道闸门的输出：
 * <ul>
 *   <li>{@link #ALLOW} - 通过，可以执行工具</li>
 *   <li>{@link #DENY} - 拒绝，立即阻止（不让用户做选择）</li>
 *   <li>{@link #ASK} - 请求用户确认（Pipeline 会调 UserApprover 把 ASK 升级为 ALLOW/DENY）</li>
 * </ul>
 */
public enum PermissionDecision {

    /** 通过，可以执行 */
    ALLOW,

    /** 拒绝，立即阻止（如 Gate 1 命中 deny list） */
    DENY,

    /** 询问用户（如 Gate 2 命中规则、需要 human-in-the-loop） */
    ASK
}
