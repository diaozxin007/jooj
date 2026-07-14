package com.xilidou.jooj.permission;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Permission 子系统(s03)的 yml → Java 桥接。
 *
 * <p>字段极简 —— 只有 mode 选一。Permission 没有派生字段/校验,豁免三分法(参考 Mcp)。
 * 直接由 {@link PermissionConfiguration} 读并 switch。
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.Permission} 拆出,前缀 {@code jooj.permission} 保持不变。
 */
@Data
@ConfigurationProperties("jooj.permission")
public class PermissionProperties {

    /**
     * 权限模式:
     * <ul>
     *   <li>{@code cli} — 默认,Gate 1 + Gate 2 + 控制台 Gate 3</li>
     *   <li>{@code web} — s22 D-10-C:ASK 冒泡到 REST /pending 前端弹框</li>
     *   <li>{@code always-allow} — 测试或 batch 场景,所有 ASK 自动通过</li>
     *   <li>{@code always-deny} — 测试场景,所有 ASK 自动拒绝</li>
     * </ul>
     */
    private String mode = "cli";
}
