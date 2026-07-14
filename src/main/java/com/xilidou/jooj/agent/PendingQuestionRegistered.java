package com.xilidou.jooj.agent;

import com.xilidou.jooj.agent.control.PendingQuestion;

/**
 * s22 SSE:{@link AgentControl#ask} 挂起 pending question 时发布,让 web 层 SSE
 * 立即 push 到浏览器,替代前端 poll /pending。
 *
 * <p>用 Spring event 解耦 agent 层与 web 层。
 *
 * @param sessionId 挂起的 session
 * @param question  pending 详情(permission / clarify)
 */
public record PendingQuestionRegistered(String sessionId, PendingQuestion question) {
}
