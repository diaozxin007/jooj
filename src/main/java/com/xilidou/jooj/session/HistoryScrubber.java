package com.xilidou.jooj.session;

import com.xilidou.jooj.compact.MicroCompactor;
import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRole;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 加载 history 时的兜底净化器(s21 Demo 25 副作用)。
 *
 * <p>背景:Provider 强制 tool_use ↔ tool_result 严格配对。一旦磁盘上的 session JSON
 * 含孤儿 tool_result(对应 tool_use 不存在),下一次发给 provider 直接 400。
 *
 * <p>策略 —— **保守、向前兼容、原子**:
 * <ol>
 *   <li>扫整个 list 收集所有 {@link LlmToolCall#getId()}</li>
 *   <li>从每条 TOOL message 里过滤 {@link LlmToolResult} 块,{@code toolCallId} 不在
 *       集合里就丢弃</li>
 *   <li>过滤后内容空的 message 整条丢</li>
 *   <li>不动任何 {@link com.xilidou.jooj.llm.domain.LlmText} /
 *       {@link com.xilidou.jooj.llm.domain.LlmThinking} 等其他 block</li>
 * </ol>
 *
 * <p>**不主动写回磁盘** —— scrub 是读盘后的运行期净化,JSON 仍是 source-of-truth。
 * 下一次正常 saveHistory 才会用净化后的列表覆盖 JSON。
 *
 * <p>反向情况(头部孤儿:tool_use 没 tool_result)provider API 同样会拒。scrub 也兜:
 * 把孤儿 tool_use 块过滤掉。
 *
 * <h3>P2 Step G:canonical 类型</h3>
 *
 * <p>老 wire 判定"role=user + content 里含 ToolResultBlock"简化为 "role == TOOL"
 * 一等判定。tool_use / tool_result 都是 canonical sealed {@link LlmContent} 子类,
 * 直接 instanceof 提取 id / toolCallId 字段。
 */
@Slf4j
public final class HistoryScrubber {

    /** MicroCompactor 老 placeholder 文案(s21 Demo 25 之前)。加载时升级到新文案。 */
    static final String LEGACY_TOOL_RESULT_PLACEHOLDER =
            "[Earlier tool result compacted. Re-run the tool if needed.]";

    /** 升级目标文案。跟 {@link MicroCompactor#PLACEHOLDER} 完全一致。 */
    static final String NEW_TOOL_RESULT_PLACEHOLDER =
            "[Earlier tool result omitted to save context. Do NOT re-run the tool unless the user explicitly asks.]";

    private HistoryScrubber() {}

    /**
     * 在 {@code history} 上做一次 self-consistent 净化,返回新 list(不原地 mutate)。
     *
     * @param history 原 history(可空 / 可不变)
     * @return 净化后的 history;不含孤儿 tool_call / tool_result
     */
    public static List<LlmMessage> scrub(List<LlmMessage> history) {
        if (history == null || history.isEmpty()) return history;

        // Pass 1: 收集所有 tool_call id 和所有 tool_result 引用的 id
        Set<String> callIds = new HashSet<>();
        Set<String> resultIds = new HashSet<>();
        boolean hasLegacyPlaceholder = false;
        for (LlmMessage m : history) {
            if (m == null || m.getContent() == null) continue;
            for (LlmContent c : m.getContent()) {
                if (c instanceof LlmToolCall tc && tc.getId() != null) {
                    callIds.add(tc.getId());
                } else if (c instanceof LlmToolResult tr) {
                    if (tr.getToolCallId() != null) resultIds.add(tr.getToolCallId());
                    if (LEGACY_TOOL_RESULT_PLACEHOLDER.equals(tr.getOutput())) {
                        hasLegacyPlaceholder = true;
                    }
                }
            }
        }

        if (callIds.isEmpty() && resultIds.isEmpty() && !hasLegacyPlaceholder) {
            return history;
        }

        // Pass 2: 过滤孤儿 + 升级老 placeholder
        List<LlmMessage> out = new ArrayList<>(history.size());
        int droppedBlocks = 0;
        int droppedMessages = 0;
        int upgradedPlaceholders = 0;
        for (LlmMessage m : history) {
            if (m == null) {
                out.add(null);
                continue;
            }
            List<LlmContent> blocks = m.getContent();
            if (blocks == null) {
                out.add(m);
                continue;
            }
            List<LlmContent> kept = new ArrayList<>(blocks.size());
            boolean changed = false;
            for (LlmContent c : blocks) {
                if (c instanceof LlmToolResult tr) {
                    String tid = tr.getToolCallId();
                    if (tid == null || !callIds.contains(tid)) {
                        droppedBlocks++;
                        changed = true;
                        continue;
                    }
                    // 升级老 placeholder 到新文案(原地 mutate:LlmToolResult 是 @Data Lombok)
                    if (LEGACY_TOOL_RESULT_PLACEHOLDER.equals(tr.getOutput())) {
                        tr.setOutput(NEW_TOOL_RESULT_PLACEHOLDER);
                        upgradedPlaceholders++;
                    }
                } else if (c instanceof LlmToolCall tc) {
                    String tid = tc.getId();
                    if (tid == null || !resultIds.contains(tid)) {
                        droppedBlocks++;
                        changed = true;
                        continue;
                    }
                }
                kept.add(c);
            }
            if (kept.isEmpty()) {
                droppedMessages++;
                continue;
            }
            if (!changed) {
                out.add(m);
            } else {
                out.add(new LlmMessage(m.getRole(), kept, m.getCacheHints()));
            }
        }

        if (droppedBlocks > 0 || droppedMessages > 0 || upgradedPlaceholders > 0) {
            log.warn("[HistoryScrub] dropped {} orphan tool block(s) + {} now-empty message(s) + " +
                            "upgraded {} legacy tool_result placeholder(s) (history size {})",
                    droppedBlocks, droppedMessages, upgradedPlaceholders, history.size());
        }
        return out;
    }

    /** LlmRole 引用保留。 */
    @SuppressWarnings("unused")
    private static void _keepRoleReferenced() {
        LlmRole unused = null;
    }
}
