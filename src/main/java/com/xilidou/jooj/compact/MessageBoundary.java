package com.xilidou.jooj.compact;

import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRole;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * tool_call ↔ tool_result 配对边界保护工具。
 *
 * <p>背景:Provider API 强制要求 assistant 消息里的 tool_call(id=X) 必须有一条
 * TOOL 消息包含 tool_result(toolCallId=X) 配对。{@link SnipCompactor} 裁中间消息时
 * 如果切口落在这对之间,会留下"孤儿":
 * <ul>
 *   <li>头部孤儿:head 内有 tool_call,但配对的 tool_result 被裁掉了 → 400</li>
 *   <li>尾部孤儿:tail 内有 tool_result,但配对的 tool_call 被裁掉了 → 400</li>
 * </ul>
 *
 * <p>策略:
 * <ul>
 *   <li>{@link #adjustHeadEnd}:头切口往后挪,把 tool_result 也保留在头部</li>
 *   <li>{@link #adjustTailStart}:尾切口往前挪,把 tool_call 也保留在尾部</li>
 * </ul>
 *
 * <p>极端情况(headEnd 推到 ≥ tailStart)由 {@link SnipCompactor} 兜底:不进行裁剪。
 *
 * <h3>P2 Step G:canonical role-based dispatch</h3>
 *
 * <p>老 wire 判定"role=user + content 含 ToolResultBlock" / "role=assistant + content
 * 含 ToolUseBlock" 现在简化为:
 * <ul>
 *   <li>TOOL 消息 → 遍历 {@link LlmToolResult}(canonical role 一等)</li>
 *   <li>ASSISTANT 消息 → 遍历 {@link LlmToolCall}</li>
 * </ul>
 * 不再需要"是不是 List<?>" / 各种 instanceof wire block 的 defensive branch。
 *
 * <p>self-consistency walk 语义保留 —— 每次扩展后扫一遍 head/tail 范围看还有没有
 * unmatched call/result,有就继续扩,能修复跨多条边界的孤儿(s21 Demo 25 加固)。
 */
public final class MessageBoundary {

    private MessageBoundary() {}

    /**
     * 头切口调整:避免在头部留下孤儿 tool_call。
     *
     * <p>self-consistency walk:扫 {@code msgs[0..headEnd)} 内所有 tool_call,如果某个
     * id 没有对应的 tool_result 在 head 范围内,就把 headEnd 往后扩一格。
     * 重复扫直到 head 自洽(或 headEnd ≥ msgs.size())。
     */
    public static int adjustHeadEnd(List<LlmMessage> msgs, int headEnd) {
        if (headEnd <= 0 || headEnd >= msgs.size()) {
            return headEnd;
        }
        int max = msgs.size();
        while (headEnd < max) {
            Set<String> openCalls = new HashSet<>();
            collectCallIds(msgs.subList(0, headEnd), openCalls);
            removeIfResultIn(msgs.subList(0, headEnd), openCalls);
            if (openCalls.isEmpty()) break;
            headEnd++;
        }
        return headEnd;
    }

    /**
     * 尾切口调整:避免在尾部留下孤儿 tool_result。
     */
    public static int adjustTailStart(List<LlmMessage> msgs, int tailStart) {
        if (tailStart <= 0 || tailStart >= msgs.size()) {
            return tailStart;
        }
        while (tailStart > 0) {
            Set<String> openResults = new HashSet<>();
            collectResultIds(msgs.subList(tailStart, msgs.size()), openResults);
            removeIfCallIn(msgs.subList(tailStart, msgs.size()), openResults);
            if (openResults.isEmpty()) break;
            tailStart--;
        }
        return tailStart;
    }

    /** 收集 messages 范围内所有 tool_call 的 id 到 set。 */
    private static void collectCallIds(List<LlmMessage> range, Set<String> out) {
        for (LlmMessage m : range) {
            if (m == null || m.getContent() == null) continue;
            for (LlmContent c : m.getContent()) {
                if (c instanceof LlmToolCall tc && tc.getId() != null) {
                    out.add(tc.getId());
                }
            }
        }
    }

    /** 收集 messages 范围内所有 tool_result 的 toolCallId 到 set。 */
    private static void collectResultIds(List<LlmMessage> range, Set<String> out) {
        for (LlmMessage m : range) {
            if (m == null || m.getContent() == null) continue;
            for (LlmContent c : m.getContent()) {
                if (c instanceof LlmToolResult tr && tr.getToolCallId() != null) {
                    out.add(tr.getToolCallId());
                }
            }
        }
    }

    /** 从 ids 中移除:range 内已存在 tool_result 引用的 X。 */
    private static void removeIfResultIn(List<LlmMessage> range, Set<String> ids) {
        if (ids.isEmpty()) return;
        for (LlmMessage m : range) {
            if (m == null || m.getContent() == null) continue;
            for (LlmContent c : m.getContent()) {
                if (c instanceof LlmToolResult tr && tr.getToolCallId() != null) {
                    ids.remove(tr.getToolCallId());
                }
            }
        }
    }

    /** 从 ids 中移除:range 内已存在 tool_call id 的 X。 */
    private static void removeIfCallIn(List<LlmMessage> range, Set<String> ids) {
        if (ids.isEmpty()) return;
        for (LlmMessage m : range) {
            if (m == null || m.getContent() == null) continue;
            for (LlmContent c : m.getContent()) {
                if (c instanceof LlmToolCall tc && tc.getId() != null) {
                    ids.remove(tc.getId());
                }
            }
        }
    }

    /** 判定:assistant 消息中是否含 tool_call block。 */
    public static boolean hasToolUse(LlmMessage m) {
        if (m == null || m.getRole() != LlmRole.ASSISTANT || m.getContent() == null) return false;
        for (LlmContent c : m.getContent()) {
            if (c instanceof LlmToolCall) return true;
        }
        return false;
    }

    /** 判定:TOOL 消息(canonical 一等判定,取代老 "role=user + content 含 ToolResultBlock")。 */
    public static boolean isToolResult(LlmMessage m) {
        return m != null && m.getRole() == LlmRole.TOOL;
    }
}
