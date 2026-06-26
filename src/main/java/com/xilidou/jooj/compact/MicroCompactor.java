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
     * 替换占位符。约 60 字符,确保 minPlaceholderLen 默认 120 不会被自身触发(幂等防护)。
     */
    static final String PLACEHOLDER =
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
            // 长度阈值过滤微小输出 + 幂等性防护(L2 自身) + 不动 L3 stub(交互边界)
            if (s.length() > config.minPlaceholderLen()
                    && !PLACEHOLDER.equals(s)
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
