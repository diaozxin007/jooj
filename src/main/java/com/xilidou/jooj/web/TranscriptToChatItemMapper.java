package com.xilidou.jooj.web;

import com.xilidou.jooj.transcript.TranscriptLine;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 transcript 域(user↔lead-agent 干净对话)翻译成前端 {@link ChatItem} 列表。
 *
 * <h3>s22 P4 语义</h3>
 *
 * <p>相比 {@link ChatHistoryMapper}(基于 raw session history + tool 展开 + memory strip
 * 等复杂逻辑),本 mapper 输入是 {@link TranscriptLine} 事件流,输出简化很多 —— 只有 3
 * 种展示项:
 *
 * <table>
 *   <caption>TranscriptLine.role → ChatItem.Type</caption>
 *   <tr><th>role</th><th>type</th><th>说明</th></tr>
 *   <tr><td>{@code "user"}</td><td>{@link ChatItem.Type#USER_INPUT}</td>
 *       <td>用户干净原文(事件流里天然没 memory prefix,不需要 strip)</td></tr>
 *   <tr><td>{@code "scheduled"}</td>
 *       <td>{@link ChatItem.Type#SYSTEM_NOTICE}({@link ChatItem.SystemNotice.Source#CRON})</td>
 *       <td>cron 触发,前端渲染系统气泡 "⏰ Scheduled by cron:jobId"</td></tr>
 *   <tr><td>{@code "assistant"}</td><td>{@link ChatItem.Type#ASSISTANT_TEXT}</td>
 *       <td>lead-agent 最终纯文本回复</td></tr>
 *   <tr><td>{@code "interrupted"}</td>
 *       <td>{@link ChatItem.Type#SYSTEM_NOTICE}({@link ChatItem.SystemNotice.Source#CRON})</td>
 *       <td>s22 D-8:用户主动打断,前端渲染系统气泡 "[已中断]" + 可选 partial content</td></tr>
 * </table>
 *
 * <p><b>失去的展示能力</b>(选择 P4 A 方案的必然代价,参考 s22 文档 §4.5):
 * <ul>
 *   <li>无 TOOL_CALL 卡 —— transcript 不记 tool 中间态(D3)</li>
 *   <li>无 THINKING 折叠 —— transcript 不记 thinking block</li>
 *   <li>无 ARCHIVE_L1 / ARCHIVE_L4 / ERROR 通知 —— 压缩和错误恢复是 loop 内部行为</li>
 *   <li>无 background task placeholder 合并 —— 同上</li>
 * </ul>
 *
 * <p>需要 tool 交互可视化的场景,应回退用 {@link ChatHistoryMapper} 直接读
 * {@code sessionService.loadHistory} 的 raw history(带污染)。
 *
 * <h3>id 生成规则</h3>
 *
 * <p>{@code tr-{index}} —— 基于 transcript 文件的行号(0-based)。稳定性:
 * transcript 是 append-only(D10),已存在的行的 index 永不变,前端 dedup 可靠。
 *
 * <p><b>为什么是纯静态:</b> 无状态、无 Spring 依赖,单测直接 {@code List.of(...)}
 * 喂进去就完事。跟 {@link ChatHistoryMapper} 保持同风格。
 */
public final class TranscriptToChatItemMapper {

    private TranscriptToChatItemMapper() {}

    /**
     * @param lines transcript 行列表,通常来自 {@code transcriptService.readAll(sid)}
     * @return ChatItem 列表,前端直接分派渲染
     */
    public static List<ChatItem> map(List<TranscriptLine> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        List<ChatItem> items = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            TranscriptLine line = lines.get(i);
            if (line == null) continue;
            // s22 D-8:interrupted 事件允许 blank content(打断时 assistant 还没出文本),
            // 其他 role 空 content 跳过
            boolean isInterrupted = "interrupted".equals(line.role());
            if (!isInterrupted && (line.content() == null || line.content().isBlank())) continue;
            ChatItem item = mapLine(line, "tr-" + i);
            if (item != null) items.add(item);
        }
        return items;
    }

    private static ChatItem mapLine(TranscriptLine line, String id) {
        String createdAt = line.timestamp() != null ? line.timestamp().toString() : null;
        return switch (line.role()) {
            case "user" -> new ChatItem(
                    id,
                    ChatItem.Type.USER_INPUT,
                    "user",
                    line.content(),
                    null, null,
                    createdAt);
            case "scheduled" -> new ChatItem(
                    id,
                    ChatItem.Type.SYSTEM_NOTICE,
                    "system",
                    null, null,
                    new ChatItem.SystemNotice(
                            ChatItem.SystemNotice.Source.CRON,
                            "⏰ Scheduled" + (line.source() != null ? " by " + line.source() : ""),
                            line.content()),
                    createdAt);
            case "assistant" -> new ChatItem(
                    id,
                    ChatItem.Type.ASSISTANT_TEXT,
                    "assistant",
                    line.content(),
                    null, null,
                    createdAt);
            case "interrupted" -> {
                // s22 D-8:用户主动打断的一轮。content 可能是 partial assistant text 或空。
                // 渲染时前端显示 "[已中断]" 主标题 + partial 作为 details(如果有)。
                // 沿用 SYSTEM_NOTICE 通道保持前端 mapping 简单,不新增 Type。
                String partial = line.content() == null ? "" : line.content();
                yield new ChatItem(
                        id,
                        ChatItem.Type.SYSTEM_NOTICE,
                        "system",
                        null, null,
                        new ChatItem.SystemNotice(
                                ChatItem.SystemNotice.Source.CRON,
                                "⛔ 已中断",
                                partial.isBlank() ? "(用户在 lead-agent 出文本前打断)" : partial),
                        createdAt);
            }
            default -> null; // 未知 role,忽略(留 forward-compat 空间)
        };
    }
}
