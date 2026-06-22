package com.xilidou.marvis.harness.hook.impl;

import com.xilidou.marvis.harness.hook.Hook;
import com.xilidou.marvis.harness.http.dto.ToolUseBlock;
import com.xilidou.marvis.harness.permission.PermissionPipeline;
import com.xilidou.marvis.harness.permission.PermissionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * PermissionHook - s03 的 permission 检查重构为 PreToolUse hook。
 *
 * <p>对应 Python s04 第 176 行的 {@code permission_hook(block)}。
 *
 * <p>核心思想（s03 → s04 的重构）：
 * <ul>
 *   <li>s03：{@code AgentLoopHarness} 硬编码调 {@code permissions.check(toolUse)}</li>
 *   <li>s04：Loop 改调 {@code hooks.triggerPreToolUse(toolUse)}，permission 逻辑搬到这个 hook 里</li>
 *   <li>结果：Loop **不再知道 permission 这个概念**——它只知道有 PreToolUse 事件，someone 阻止了。
 *       将来加 metric hook、speed-limit hook 都不需要改 Loop。</li>
 * </ul>
 *
 * <p>这个 Hook 复用了已经写好的 {@link PermissionPipeline}（含 3 道闸门 + 用户审批），
 * 只是把"调用方式"从直接 method call 改成"通过 hook 总线"。
 *
 * <p>构造器注入 {@link PermissionPipeline}：在 Spring 容器场景下需要先有 PermissionPipeline Bean。
 * 当前 PermissionPipeline 还不是 Bean（手工 fromEnv 构造），所以本 Hook 提供两套构造器，
 * Spring 化 Step 2/3 完成后会让 Pipeline 也变成 Bean。
 */
@Component
@Slf4j
public class PermissionHook implements Hook.OnPreToolUse {

    private final PermissionPipeline pipeline;

    /**
     * Spring 注入构造器。如果项目里有 PermissionPipeline Bean（Spring 化完成后），自动用它。
     *
     * <p>过渡期（当前）：PermissionPipeline 不是 Bean，会用 fallback 构造器。
     */
    public PermissionHook(PermissionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * 默认构造器：用 {@link PermissionPipeline#defaultCli()}。
     *
     * <p>过渡期由这个起作用——Spring 看到 PermissionPipeline 没 Bean，会调这个无参构造器。
     * 等 Spring 化完成后，删掉这个，强制用 DI。
     */
    public PermissionHook() {
        this(PermissionPipeline.defaultCli());
    }

    @Override
    public Optional<String> handle(ToolUseBlock toolUse) {
        PermissionResult result = pipeline.check(toolUse);

        if (result.isDeny()) {
            String denyMsg = "Permission denied: " + result.getReason();
            log.info("[Hook] PermissionHook DENY {}: {}", toolUse.getName(), denyMsg);
            return Optional.of(denyMsg);   // ← 阻止 loop 执行该工具
        }

        // ALLOW（Pipeline 不会返回 ASK，因为 Gate 3 把 ASK 转成了 ALLOW/DENY）
        return Optional.empty();
    }
}
