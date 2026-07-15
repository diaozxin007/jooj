package com.xilidou.jooj.compact;

import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRole;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * L2 micro_compact:旧 tool_result 内容替换为占位符。
 *
 * <p>策略:
 * <ol>
 *   <li>按时间序收集 messages 里所有 {@link LlmToolResult}</li>
 *   <li>保留最后 {@link CompactConfig#keepRecent()} 个原文</li>
 *   <li>更早的若 output 长度 &gt; {@link CompactConfig#minPlaceholderLen()},
 *       直接 mutate {@code LlmToolResult.output = PLACEHOLDER}</li>
 * </ol>
 *
 * <p>幂等:已经是占位符的不重复替换。
 *
 * <h3>P2 Step G:canonical 一等 TOOL 判定</h3>
 *
 * <p>老实现遍历 role="user" 消息里的 content 找 ToolResultBlock,现在简化为遍历
 * role={@link LlmRole#TOOL} 消息里的 LlmToolResult 子类 —— 只这一种 shape,
 * 完全消除 "content 是 String 还是 List<?>" 的 defensive branch。
 */
@Slf4j
public class MicroCompactor {

    /**
     * 替换占位符。
     *
     * <p>s21 Demo 25 副作用 v5:**绝不诱导 LLM 重跑工具**。
     * 老文案 {@code "Earlier tool result compacted. Re-run the tool if needed."} 是
     * 死循环邀请函 —— LLM 看到 "Re-run if needed" 会真的重跑 → 新 tool_result 又
     * 被 L2 压缩 → LLM 看到 placeholder 又重跑 → 死循环烧钱。
     * 新文案显式禁止重跑,让 LLM 把 placeholder 当"已知不可见内容"处理。
     */
    public static final String PLACEHOLDER =
            "[Earlier tool result omitted to save context. Do NOT re-run the tool unless the user explicitly asks.]";

    /** 老文案(s21 Demo 25 之前)— 仅用于幂等识别,不再写入新数据。 */
    public static final String LEGACY_PLACEHOLDER =
            "[Earlier tool result compacted. Re-run the tool if needed.]";

    private final CompactConfig config;

    public MicroCompactor(CompactConfig config) {
        this.config = config;
    }

    /**
     * 收集 + 替换。返回 true 表示至少替换了一个 tool_result。
     *
     * @param messages 对话历史(本方法可能 mutate 内部 LlmToolResult.output)
     * @return 是否实际替换过
     */
    public boolean apply(List<LlmMessage> messages) {
        List<LlmToolResult> all = collectToolResults(messages);
        if (all.size() <= config.keepRecent()) {
            return false;
        }

        int compacted = 0;
        int compactRange = all.size() - config.keepRecent();
        for (int i = 0; i < compactRange; i++) {
            LlmToolResult b = all.get(i);
            String s = b.getOutput() != null ? b.getOutput() : "";
            // 长度阈值过滤微小输出 + 幂等性防护(同时认新老文案) + 不动 L3 stub
            if (s.length() > config.minPlaceholderLen()
                    && !PLACEHOLDER.equals(s)
                    && !LEGACY_PLACEHOLDER.equals(s)
                    && !s.startsWith(BudgetCompactor.STUB_PREFIX)) {
                b.setOutput(PLACEHOLDER);
                compacted++;
            }
        }
        if (compacted > 0) {
            log.info("[Compact L2] micro compacted {} old tool results (kept last {})",
                    compacted, config.keepRecent());
            return true;
        }
        return false;
    }

    /**
     * 按时间序遍历 messages,收集所有 LlmToolResult。
     *
     * <p>tool_result 必须在 {@link LlmRole#TOOL} 消息里(canonical 一等约束)。
     */
    private static List<LlmToolResult> collectToolResults(List<LlmMessage> msgs) {
        List<LlmToolResult> out = new ArrayList<>();
        for (LlmMessage m : msgs) {
            if (m == null || m.getRole() != LlmRole.TOOL || m.getContent() == null) continue;
            for (LlmContent c : m.getContent()) {
                if (c instanceof LlmToolResult tr) {
                    out.add(tr);
                }
            }
        }
        return out;
    }
}
