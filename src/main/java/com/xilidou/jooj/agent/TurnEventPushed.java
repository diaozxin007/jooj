package com.xilidou.jooj.agent;

/**
 * s22 SSE:{@link TurnEventStream#push} 事件发布 —— 让 web 层监听后通过 SSE
 * 把摘要 push 到浏览器,替代前端 poll /events。
 *
 * <p>用 Spring event 而不是直接依赖:
 * <ul>
 *   <li>agent 层不需要认识 SseStreamService(web 层的类)</li>
 *   <li>测试路径可以完全不装 listener</li>
 *   <li>未来加其他 listener(比如 tracing / metrics)不改 publisher</li>
 * </ul>
 *
 * @param sessionId  哪个 session 的事件
 * @param event      TurnEvent(已带 seq / at / type / summary)
 */
public record TurnEventPushed(String sessionId, TurnEvent event) {
}
