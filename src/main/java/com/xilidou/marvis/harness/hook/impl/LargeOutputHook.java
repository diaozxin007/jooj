package com.xilidou.marvis.harness.hook.impl;

import com.xilidou.marvis.harness.hook.Hook;
import com.xilidou.marvis.harness.http.dto.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * LargeOutputHook - PostToolUse 事件 hook，警告大输出。
 *
 * <p>对应 Python s04 第 206 行的 {@code large_output_hook(block, output)}。
 *
 * <p>当工具输出超过 {@link #LARGE_OUTPUT_THRESHOLD} 时打 warn 日志。
 *
 * <p>用途：
 * <ul>
 *   <li>提醒开发者：某工具生成巨大输出，可能撑爆 LLM context</li>
 *   <li>未来扩展：可以记录到 metric，或者直接截断输出（返回非空 Optional 阻止）</li>
 * </ul>
 *
 * <p>当前实现是**纯 observability**，永远返回 empty——不阻止 loop。
 */
@Component
@Slf4j
public class LargeOutputHook implements Hook.OnPostToolUse {

    /** 输出超过这个字符数时告警（与 BashTool 的 50000 截断一致，作为提醒阈值）*/
    private static final int LARGE_OUTPUT_THRESHOLD = 10000;

    @Override
    public Optional<String> handle(ToolUseBlock toolUse, String output) {
        if (output != null && output.length() > LARGE_OUTPUT_THRESHOLD) {
            log.warn("[Hook] PostToolUse: large output from {} ({} chars)",
                    toolUse.getName(), output.length());
        }
        return Optional.empty();
    }
}
