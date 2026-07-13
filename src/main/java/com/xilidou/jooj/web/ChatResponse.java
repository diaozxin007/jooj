package com.xilidou.jooj.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * POST /api/chat 的响应体。
 *
 * <p>字段:
 * <ul>
 *   <li>{@code reply} —— assistant 最后一轮的纯文本(从 history.last 的 TextBlock 拼出)。
 *       向后兼容 channel 侧,web 前端应优先使用 {@code newItems}。</li>
 *   <li>{@code historySize} —— 当前 history.size(),前端显示对话长度</li>
 *   <li>{@code toolCalls} —— 本次 turn 调用的工具名。channel 侧简单展示用</li>
 *   <li>{@code newItems} —— 本回合(historyBefore..end)在展示层新增的
 *       {@link ChatItem} 列表。前端直接 append,取代 "reply || '(no reply)'" 逻辑</li>
 * </ul>
 *
 * <p>不返完整 history —— 通过单独 GET /api/chat-history endpoint 拿。让 chat 响应保持紧凑。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private String reply;
    private int historySize;
    private List<String> toolCalls;
    private List<ChatItem> newItems;
}
