package com.xilidou.marvis.compact;

import com.xilidou.marvis.http.dto.MessageParam;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * L1 snip_compact：消息条数超阈值时,裁中间留头尾。
 *
 * <p>策略：
 * <ol>
 *   <li>消息总数 ≤ {@link CompactConfig#maxMessages()} → 不动</li>
 *   <li>头部保留前 {@link CompactConfig#snipHeadKeep()} 条</li>
 *   <li>尾部保留后 {@code maxMessages - snipHeadKeep} 条</li>
 *   <li>{@link MessageBoundary} 调整切口,保护 tool_use ↔ tool_result 配对</li>
 *   <li>中间替换为一条 user("[snipped N messages]") 占位</li>
 * </ol>
 *
 * <p>替换占位为 {@code role=user},不是 {@code role=system},因为
 * Anthropic Messages API 只接受 user/assistant 两种 role(system 单独传)。
 *
 * <p>边界情况：调整后 headEnd ≥ tailStart 时不裁(头尾交叠或贴合)。
 *
 * <p>不会原地 mutate 任何 MessageParam,只 {@code messages.clear()} +
 * {@code messages.addAll(rebuilt)} 重建列表。被保留的 MessageParam 引用
 * 是同一个,但作为列表条目本身被替换。
 */
@Slf4j
public class SnipCompactor {

    private final CompactConfig config;

    public SnipCompactor(CompactConfig config) {
        this.config = config;
    }

    /**
     * 在 {@code messages} 上原地裁剪。返回 true 表示发生了裁剪。
     *
     * @param messages 对话历史(会被原地修改:替换为新 list 内容)
     * @return 是否实际修改了 messages
     */
    public boolean apply(List<MessageParam> messages) {
        int total = messages.size();
        if (total <= config.maxMessages()) {
            return false;
        }

        int headEnd = config.snipHeadKeep();
        int tailStart = total - (config.maxMessages() - config.snipHeadKeep());

        // 边界保护：tool_use ↔ tool_result 不能拆
        headEnd = MessageBoundary.adjustHeadEnd(messages, headEnd);
        tailStart = MessageBoundary.adjustTailStart(messages, tailStart);

        if (headEnd >= tailStart) {
            // 调整后头尾交叠或贴合,无中间可裁
            return false;
        }

        int snipped = tailStart - headEnd;

        // 用新 list 重建,不原地 mutate 单条 MessageParam(保护 snapshot 引用)
        List<MessageParam> rebuilt = new ArrayList<>(messages.subList(0, headEnd));
        rebuilt.add(MessageParam.user("[snipped " + snipped + " messages]"));
        rebuilt.addAll(messages.subList(tailStart, total));

        messages.clear();
        messages.addAll(rebuilt);

        log.info("[Compact L1] snip removed {} middle messages, total {} → {}",
                snipped, total, rebuilt.size());
        return true;
    }
}
