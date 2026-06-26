package com.xilidou.jooj.hook;

import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.ToolUseBlock;

import java.util.List;
import java.util.Optional;

/**
 * Hook 接口家族——4 个事件、4 个嵌套接口。
 *
 * <p>对应 Python s04 的 hook 函数签名。Python 用动态 {@code *args}，Java 用 4 个
 * 类型安全的接口：每个 hook 实现类只对自己感兴趣的事件实现对应的方法。
 *
 * <h3>"Block" 语义</h3>
 *
 * <p>所有 hook 方法返回 {@code Optional<String>}：
 * <ul>
 *   <li>{@code Optional.empty()} → 不阻止，loop 继续（最常见，对应 Python {@code return None}）</li>
 *   <li>{@code Optional.of("reason")} → 阻止，{@code reason} 会被 loop 用来构造 tool_result
 *       回传给 LLM（对应 Python {@code return "Permission denied"}）</li>
 * </ul>
 *
 * <p>{@link HookManager#trigger} 短路语义：第一个返回非空 Optional 的 hook 立即终止后续 hook 调用。
 *
 * <h3>为什么不用单接口</h3>
 *
 * <p>4 个事件参数类型不同（String / ToolUseBlock / String+ToolUseBlock / List<Message>）。
 * 单接口必然走 {@code Object...} 失去类型安全。考虑到事件就 4 个，分开声明更清晰。
 */
public class Hook {

    private Hook() { /* 仅作命名空间，不实例化 */ }

    /**
     * UserPromptSubmit 事件 hook。
     * <p>用户输入用户输入后、发给 LLM 前触发。
     */
    @FunctionalInterface
    public interface OnUserPrompt {
        Optional<String> handle(String query);
    }

    /**
     * PreToolUse 事件 hook。
     * <p>每个 tool_use 执行前触发。这是 s04 的核心事件——s03 的 permission 逻辑会重构到这里。
     */
    @FunctionalInterface
    public interface OnPreToolUse {
        Optional<String> handle(ToolUseBlock toolUse);
    }

    /**
     * PostToolUse 事件 hook。
     * <p>每个 tool 执行后触发。output 是工具输出。
     */
    @FunctionalInterface
    public interface OnPostToolUse {
        Optional<String> handle(ToolUseBlock toolUse, String output);
    }

    /**
     * Stop 事件 hook。
     * <p>Loop 准备退出（end_turn / max_tokens / stop_sequence）时触发。
     *
     * <p>返回非空 Optional 的特殊语义：**强制 loop 再来一轮**。
     * 用来做"模型说完了但我们觉得任务没完，再追问一句"的高级场景。
     */
    @FunctionalInterface
    public interface OnStop {
        Optional<String> handle(List<MessageParam> messages);
    }
}
