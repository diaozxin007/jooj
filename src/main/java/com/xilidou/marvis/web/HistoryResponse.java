package com.xilidou.marvis.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GET /api/history 的响应体 —— 完整对话历史(简化版,只给前端展示用)。
 *
 * <p>每条 message 抽成一个 simple {@link Entry} record,role + 拼好的纯文本。
 * 不返协议层 ContentBlock 细节(tool_use/tool_result/thinking 等)— 那些是
 * Anthropic 协议内部状态,前端不需要知道。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoryResponse {

    /** 已揉平的对话条目列表,按时间序。 */
    private List<Entry> messages;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        /** "user" / "assistant"。 */
        private String role;
        /** 拼接后的纯文本(text block 直接、tool_use 拼成 "[tool: bash(...)]" 占位等)。 */
        private String text;
    }
}
