package com.xilidou.marvis.permission;

import com.xilidou.marvis.MarvisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Permission 子系统的 Spring 装配 —— R3 重构(2026-06-24)从
 * {@code harness.MarvisBeansConfig} 拆出。
 *
 * <p>提供 1 个 Bean:
 * <ul>
 *   <li>{@link PermissionPipeline} —— 三选一(cli / always-allow / always-deny)</li>
 * </ul>
 */
@Configuration
public class PermissionConfiguration {

    /**
     * 三选一构造 {@link PermissionPipeline}:
     * <ul>
     *   <li>{@code cli} — 默认(交互式控制台审批,3 道闸门 + 用户审批)</li>
     *   <li>{@code always-allow} — 测试 / batch 场景</li>
     *   <li>{@code always-deny} — 测试场景</li>
     * </ul>
     *
     * <p>未知模式回退到 {@code cli}(留下"配错也能跑"的鲁棒性)。
     */
    @Bean
    public PermissionPipeline permissionPipeline(MarvisProperties props) {
        String mode = props.getPermission().getMode();
        return switch (mode == null ? "cli" : mode) {
            case "always-allow" -> PermissionPipeline.alwaysAllow();
            case "always-deny" -> PermissionPipeline.alwaysDeny();
            default -> PermissionPipeline.defaultCli();
        };
    }
}
