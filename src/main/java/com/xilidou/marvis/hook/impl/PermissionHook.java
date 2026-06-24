package com.xilidou.marvis.hook.impl;

import com.xilidou.marvis.hook.Hook;
import com.xilidou.marvis.http.dto.ToolUseBlock;
import com.xilidou.marvis.permission.PermissionPipeline;
import com.xilidou.marvis.permission.PermissionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * PermissionHook - s03 的 permission 检查重构为 PreToolUse hook。
 *
 * <p>对应 Python s04 第 176 行的 {@code permission_hook(block)}。
 *
 * <p>核心思想(s03 → s04 的重构):
 * <ul>
 *   <li>s03:{@code AgentLoopHarness} 硬编码调 {@code permissions.check(toolUse)}</li>
 *   <li>s04:Loop 改调 {@code hooks.triggerPreToolUse(toolUse)},permission 逻辑搬到这个 hook 里</li>
 *   <li>结果:Loop **不再知道 permission 这个概念**——它只知道有 PreToolUse 事件,someone 阻止了。
 *       将来加 metric hook、speed-limit hook 都不需要改 Loop。</li>
 * </ul>
 *
 * <p>这个 Hook 复用了已经写好的 {@link PermissionPipeline}(含 3 道闸门 + 用户审批),
 * 只是把"调用方式"从直接 method call 改成"通过 hook 总线"。
 *
 * <p>切片 C-step3 之后:{@link PermissionPipeline} 已经是 Spring Bean
 * (由 {@link com.xilidou.marvis.permission.PermissionConfiguration#permissionPipeline} 提供),
 * 这里通过构造器注入即可,no-arg fallback 已删除。
 */
@Component
@Slf4j
public class PermissionHook implements Hook.OnPreToolUse {

    private final PermissionPipeline pipeline;

    public PermissionHook(PermissionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public Optional<String> handle(ToolUseBlock toolUse) {
        PermissionResult result = pipeline.check(toolUse);

        if (result.isDeny()) {
            String denyMsg = "Permission denied: " + result.getReason();
            log.info("[Hook] PermissionHook DENY {}: {}", toolUse.getName(), denyMsg);
            return Optional.of(denyMsg);   // ← 阻止 loop 执行该工具
        }

        // ALLOW(Pipeline 不会返回 ASK,因为 Gate 3 把 ASK 转成了 ALLOW/DENY)
        return Optional.empty();
    }
}
