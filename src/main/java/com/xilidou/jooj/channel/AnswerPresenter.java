package com.xilidou.jooj.channel;

import com.xilidou.jooj.agent.control.PendingQuestion;

/**
 * s22 D-12:{@link PendingQuestion} 呈现层抽象 —— 让每个 channel 决定如何把 question
 * 展示给用户。
 *
 * <h3>为什么需要这一层</h3>
 *
 * <p>D-10-B 建 ask 机制时假设"有 web 前端能弹框",实际上 jooj 支持多 channel:
 * <ul>
 *   <li>web / SSE — 结构化弹框</li>
 *   <li>weixin / discord / telegram / ... — 只有文本消息</li>
 *   <li>cli / repl — 走 stdin</li>
 * </ul>
 *
 * <p>抽 Presenter 接口后,每个 channel 一实现 —— {@code PresenterRegistry}
 * (@EventListener 收到 PendingQuestionRegistered)按 {@link #supports} 分派。
 *
 * <h3>协议</h3>
 *
 * <ul>
 *   <li>{@link #supports}:presenter 声称能处理这个 sid(通常按 sid 前缀 / channel meta 判断)</li>
 *   <li>{@link #present}:实际呈现给用户 —— 弹框、发消息、打印 stdout 等</li>
 * </ul>
 *
 * <p><b>Presenter 不负责收 answer</b>:用户答复通过各 channel 的 native inbound
 * (REST POST / IM inbound message / stdin) 走到 {@code AgentControl.answer}。
 * Presenter 只管**输出 → 用户**这一半。
 */
public interface AnswerPresenter {

    /**
     * 声称能处理这个 sid 的 pending question。同一 event 会广播给所有 presenter,
     * 每个自己判断。典型判断:sid 前缀 / question 的 originChannel 字段。
     */
    boolean supports(String sessionId, PendingQuestion question);

    /**
     * 呈现 question 给用户。**必须非阻塞** —— 已在 @EventListener 线程,不能 block。
     * 长操作用 async。
     */
    void present(String sessionId, PendingQuestion question);
}
