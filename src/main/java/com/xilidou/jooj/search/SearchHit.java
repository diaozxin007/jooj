package com.xilidou.jooj.search;

/**
 * 一条 FTS5 搜索命中。
 *
 * <p>所有元数据字段都来自 SearchStore 的 fts table 的 UNINDEXED 列(读出来不进 token,但能 filter)。
 *
 * @param sessionId  来自哪条 session(sanitized id,如 {@code chat_weixin_xxx})
 * @param msgIndex   message 在 history 列表里的下标(0-based,saveHistory 时的)
 * @param blockIndex content block 在该 message 内的下标(MessageParam.content 是 String 时为 0)
 * @param role       {@code user} 或 {@code assistant}
 * @param kind       {@code text}(普通文本)或 {@code tool_result}(工具执行结果)
 * @param toolName   仅 tool_result 才有的字段 —— 上一条 assistant message 里 ToolUseBlock 的 name
 * @param toolUseId  仅 tool_result 才有的字段 —— 关联的 tool_use id(toolu_xxx)
 * @param savedAt    saveHistory 落盘时的 epoch 毫秒
 * @param snippet    FTS5 {@code snippet()} 函数生成的高亮片段(含 {@code <b>...</b>} 包裹)
 */
public record SearchHit(
        String sessionId,
        int msgIndex,
        int blockIndex,
        String role,
        String kind,
        String toolName,
        String toolUseId,
        long savedAt,
        String snippet
) {
}
