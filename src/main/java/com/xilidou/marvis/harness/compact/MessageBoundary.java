package com.xilidou.marvis.harness.compact;

import com.xilidou.marvis.harness.http.dto.ContentBlock;
import com.xilidou.marvis.harness.http.dto.MessageParam;
import com.xilidou.marvis.harness.http.dto.ToolResultBlock;
import com.xilidou.marvis.harness.http.dto.ToolUseBlock;

import java.util.List;

/**
 * tool_use ↔ tool_result 配对边界保护工具。
 *
 * <p>背景：Anthropic Messages API 强制要求 assistant.tool_use(id=X) 必须紧跟一条
 * user.tool_result(tool_use_id=X)。{@link SnipCompactor} 裁中间消息时如果切口落在
 * 这对消息之间,会留下"孤儿":
 * <ul>
 *   <li>头部孤儿：head 末尾是 assistant(tool_use),但 tool_result 被裁掉了 → 400 错误</li>
 *   <li>尾部孤儿：tail 开头是 user(tool_result),但 tool_use 被裁掉了 → 400 错误</li>
 * </ul>
 *
 * <p>策略：
 * <ul>
 *   <li>{@link #adjustHeadEnd}：头切口往后挪,把 tool_result 也保留在头部</li>
 *   <li>{@link #adjustTailStart}：尾切口往前挪,把 tool_use 也保留在尾部</li>
 * </ul>
 *
 * <p>极端情况（headEnd 推到 ≥ tailStart）由 {@link SnipCompactor} 兜底:
 * 不进行裁剪。
 */
public final class MessageBoundary {

    private MessageBoundary() {
        // 工具类禁止实例化
    }

    /**
     * 头切口调整：避免在头部留下孤儿 tool_use。
     *
     * <p>如果 {@code msgs[headEnd-1]} 是 assistant 含 tool_use,则把 headEnd 后移
     * 直到跳过它对应的所有 tool_result（连续的 user-tool_result block）。
     *
     * @param msgs    完整消息列表
     * @param headEnd 当前头切口（exclusive,即 msgs[0..headEnd) 是头部保留区）
     * @return 调整后的 headEnd（≥ 原值）
     */
    public static int adjustHeadEnd(List<MessageParam> msgs, int headEnd) {
        if (headEnd <= 0 || headEnd >= msgs.size()) {
            return headEnd;
        }
        if (hasToolUse(msgs.get(headEnd - 1))) {
            // 把紧跟着的所有 tool_result message 都吃掉
            while (headEnd < msgs.size() && isToolResult(msgs.get(headEnd))) {
                headEnd++;
            }
        }
        return headEnd;
    }

    /**
     * 尾切口调整：避免在尾部留下孤儿 tool_result。
     *
     * <p>如果 {@code msgs[tailStart]} 是 user 含 tool_result,且
     * {@code msgs[tailStart-1]} 是 assistant 含 tool_use,则把 tailStart 前移
     * 一格,把 tool_use 也带进尾部保留区。
     *
     * @param msgs      完整消息列表
     * @param tailStart 当前尾切口（inclusive,即 msgs[tailStart..) 是尾部保留区）
     * @return 调整后的 tailStart（≤ 原值）
     */
    public static int adjustTailStart(List<MessageParam> msgs, int tailStart) {
        if (tailStart <= 0 || tailStart >= msgs.size()) {
            return tailStart;
        }
        if (isToolResult(msgs.get(tailStart)) && hasToolUse(msgs.get(tailStart - 1))) {
            tailStart--;
        }
        return tailStart;
    }

    /** 判定：assistant 消息中是否含 tool_use block。*/
    public static boolean hasToolUse(MessageParam m) {
        if (!"assistant".equals(m.getRole())) return false;
        if (!(m.getContent() instanceof List<?> blocks)) return false;
        for (Object b : blocks) {
            if (b instanceof ToolUseBlock) return true;
        }
        return false;
    }

    /** 判定：user 消息中是否含 tool_result block。*/
    public static boolean isToolResult(MessageParam m) {
        if (!"user".equals(m.getRole())) return false;
        if (!(m.getContent() instanceof List<?> blocks)) return false;
        for (Object b : blocks) {
            if (b instanceof ToolResultBlock) return true;
        }
        return false;
    }

    /**
     * 判定一个 ContentBlock 是否是 tool_use（给 MicroCompactor 用,虽然它现在不用）。
     * 保留为静态工具,未来 L3 budget 可能需要。
     */
    @SuppressWarnings("unused")
    static boolean isToolUseBlock(ContentBlock b) {
        return b instanceof ToolUseBlock;
    }
}
