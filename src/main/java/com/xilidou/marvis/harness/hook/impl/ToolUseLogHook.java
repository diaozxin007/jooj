package com.xilidou.marvis.harness.hook.impl;

import com.xilidou.marvis.harness.hook.Hook;
import com.xilidou.marvis.harness.http.dto.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * ToolUseLogHook - PreToolUse 事件的日志 hook。
 *
 * <p>对应 Python s04 第 200 行的 {@code log_hook(block)}。
 *
 * <p>每个工具调用前 log 一行——纯 observability，不阻止任何东西，永远返回 empty。
 *
 * <p>**注册顺序**：和 {@link PermissionHook} 同为 PreToolUse，
 * 谁先注册谁先跑。当前两者都是 {@code @Component}，注册顺序由 Spring Bean 创建顺序决定，
 * 通常按类名字母序：PermissionHook (P) < ToolUseLogHook (T)，所以 Permission 先跑，被拒的工具不会被 log。
 *
 * <p>如果想强制 log 在前（永远 log，包括被拒的），可以加 {@code @Order(1)} 让 Spring 优先创建。
 */
@Component
@Slf4j
public class ToolUseLogHook implements Hook.OnPreToolUse {

    @Override
    public Optional<String> handle(ToolUseBlock toolUse) {
        // 不暴露完整 input（可能含敏感信息），只 preview 前 60 字
        String inputPreview = String.valueOf(toolUse.getInput());
        if (inputPreview.length() > 60) {
            inputPreview = inputPreview.substring(0, 60) + "...";
        }
        log.info("[Hook] PreToolUse: {} {}", toolUse.getName(), inputPreview);
        return Optional.empty();
    }
}
