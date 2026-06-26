package com.xilidou.jooj.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * POST /api/chat 的响应体。
 *
 * <p>三个字段:
 * <ul>
 *   <li>{@code reply} —— assistant 最后一轮的纯文本(从 history.last 的 TextBlock 拼出)</li>
 *   <li>{@code historySize} —— 当前 history.size(),前端显示对话长度</li>
 *   <li>{@code toolCalls} —— 本次 turn 调用的工具名(可选,前端可显示"调用了 5 个工具"
 *       让用户知道 agent 干了啥)。空列表 = 直接 end_turn 没调工具</li>
 * </ul>
 *
 * <p>不返完整 history —— 通过单独 GET /api/history endpoint 拿。让 chat 响应保持紧凑。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private String reply;
    private int historySize;
    private List<String> toolCalls;
}
