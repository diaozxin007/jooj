package com.xilidou.jooj.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GET /api/chat-history 的响应 —— 展示层完整对话历史。
 *
 * <p>与 {@link HistoryResponse}(raw {role,text} 二元组)相互独立:
 * <ul>
 *   <li>{@link HistoryResponse} = raw session history 的一维投影,供内部调试</li>
 *   <li>{@link ChatHistoryResponse} = 展示层项,前端主渲染路径</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistoryResponse {
    private List<ChatItem> items;
}
