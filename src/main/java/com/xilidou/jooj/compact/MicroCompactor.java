package com.xilidou.jooj.compact;

import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * L2 micro_compact：旧 tool_result 内容替换为占位符。
 *
 * <p>策略：
 * <ol>
 *   <li>按时间序收集 messages 里所有 tool_result block</li>
 *   <li>保留最后 {@link CompactConfig#keepRecent()} 个原文</li>
 *   <li>更早的若 content 长度 > {@link CompactConfig#minPlaceholderLen()},
 *       直接 mutate {@code ToolResultBlock.content = PLACEHOLDER}</li>
 * </ol>
 *
 * <p>幂等：已经是占位符的不重复替换。
 *
 * <p>原地 mutate 而非 set 替换:
 * <ul>
 *   <li>{@link ToolResultBlock} 是 {@code @Data} Lombok 类,setContent 公开</li>
 *   <li>它在 {@code List<ContentBlock>} 里,不动 MessageParam 本身</li>
 *   <li>这跟 {@link SnipCompactor} 的策略不冲突——SnipCompactor 重建 messages list,
 *       MicroCompactor 修改 list 里某个 ToolResultBlock 的字段;两者作用对象不同</li>
 *   <li>注意:{@code MockAnthropicClient.snapshot()} 复制 messages list,但 list 里的
 *       ToolResultBlock 对象是同一引用——下一轮 LLM 调用看到的是已替换的占位符,
 *       这正是我们想要的(目的就是让 LLM 看到占位符以省 token)</li>
 * </ul>
 */
@Slf4j
public class MicroCompactor {

    /**
     * 替换占位符。
     *
     * <p>s21 Demo 25 副作用 v5:**绝不诱导 LLM 重跑工具**。
     * 老文案 {@code "Earlier tool result compacted. Re-run the tool if needed."} 是
     * 个**死循环邀请函** —— LLM 看到 "Re-run if needed" 会真的重跑 → 新 tool_result 又
     * 被 L2 压缩 → LLM 看到 placeholder 又重跑 → 死循环烧钱。实战撞过(微信里问"讲解
     * hermes 异常恢复",LLM 4 秒一轮重跑同样 cat / sed,几分钟烧 30K+ tokens)。
     *
     * <p>新文案显式禁止重跑(除非用户明确要求),让 LLM 把 placeholder 当 "已知不可见
     * 内容" 处理而不是 "需要补一刀" 的暗示。
     *
     * <p>幂等:同时识别 {@link #PLACEHOLDER}(新文案)和 {@link #LEGACY_PLACEHOLDER}
     * (老文案,磁盘上的 history 可能仍带这个值)—— 两者都不再触发替换,避免老 history
     * 加载后被无限替换成新 placeholder。
     */
    public static final String PLACEHOLDER =
            "[Earlier tool result omitted to save context. Do NOT re-run the tool unless the user explicitly asks.]";

    /** 老文案(s21 Demo 25 之前)— 仅用于幂等识别,不再写入新数据。public 是为了让
     *  HistoryScrubber 的跨边界一致性测试能直接对照。 */
    public static final String LEGACY_PLACEHOLDER =
            "[Earlier tool result compacted. Re-run the tool if needed.]";

    private final CompactConfig config;

    public MicroCompactor(CompactConfig config) {
        this.config = config;
    }

    /**
     * 收集 + 替换。返回 true 表示至少替换了一个 tool_result。
     *
     * @param messages 对话历史(本方法可能 mutate 内部 ToolResultBlock.content)
     * @return 是否实际替换过
     */
    public boolean apply(List<MessageParam> messages) {
        List<ToolResultBlock> all = collectToolResults(messages);
        if (all.size() <= config.keepRecent()) {
            return false;
        }

        int compacted = 0;
        int compactRange = all.size() - config.keepRecent();
        for (int i = 0; i < compactRange; i++) {
            ToolResultBlock b = all.get(i);
            String s = String.valueOf(b.getContent());
            // 长度阈值过滤微小输出 + 幂等性防护(同时认新老文案) + 不动 L3 stub(交互边界)
            if (s.length() > config.minPlaceholderLen()
                    && !PLACEHOLDER.equals(s)
                    && !LEGACY_PLACEHOLDER.equals(s)
                    && !s.startsWith(BudgetCompactor.STUB_PREFIX)) {
                b.setContent(PLACEHOLDER);
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
     * 按时间序遍历 messages,收集所有 tool_result block。
     *
     * <p>tool_result 必须包在 {@code role=user} 消息里(协议约束),
     * content 是 {@code List<ContentBlock>}。
     */
    private static List<ToolResultBlock> collectToolResults(List<MessageParam> msgs) {
        List<ToolResultBlock> out = new ArrayList<>();
        for (MessageParam m : msgs) {
            if (!"user".equals(m.getRole())) continue;
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object b : blocks) {
                if (b instanceof ToolResultBlock trb) {
                    out.add(trb);
                }
            }
        }
        return out;
    }
}
