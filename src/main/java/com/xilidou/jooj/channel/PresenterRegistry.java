package com.xilidou.jooj.channel;

import com.xilidou.jooj.agent.PendingQuestionRegistered;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * s22 D-12:{@link PendingQuestionRegistered} 事件分派器 —— 找到匹配的
 * {@link AnswerPresenter} 呈现给用户。
 *
 * <h3>为什么单独一个 Registry</h3>
 *
 * <p>不让每个 Presenter 自己 @EventListener,原因:
 * <ol>
 *   <li>**至多一个呈现** —— 如果 SSE + 微信同时监听同一 event,pending 会被两个 UI
 *       都呈现,用户困惑。Registry 找到第一个 supports 的就停</li>
 *   <li>**未匹配 warn** —— 如果没 presenter supports,记 warn(agent 会在 3min 后 timeout)</li>
 * </ol>
 *
 * <p>顺序不保证 —— Spring 注入 List 顺序按 Bean 名字母序。若未来需要 priority,加
 * {@code @Order} 或 Presenter 增加 priority 方法。当前只有 SSE / weixin 两个,不冲突。
 */
@Component
@Slf4j
public class PresenterRegistry {

    private final List<AnswerPresenter> presenters;

    public PresenterRegistry(List<AnswerPresenter> presenters) {
        this.presenters = presenters;
        log.info("[PresenterRegistry] registered {} presenter(s): {}",
                presenters.size(),
                presenters.stream().map(p -> p.getClass().getSimpleName()).toList());
    }

    @EventListener
    void onPendingQuestion(PendingQuestionRegistered evt) {
        for (AnswerPresenter p : presenters) {
            if (p.supports(evt.sessionId(), evt.question())) {
                log.debug("[PresenterRegistry] dispatching sid={} askId={} → {}",
                        evt.sessionId(), evt.question().askId(),
                        p.getClass().getSimpleName());
                try {
                    p.present(evt.sessionId(), evt.question());
                } catch (Throwable t) {
                    log.warn("[PresenterRegistry] presenter {} threw for sid={} askId={}: {}",
                            p.getClass().getSimpleName(), evt.sessionId(),
                            evt.question().askId(), t.toString());
                }
                return;
            }
        }
        log.warn("[PresenterRegistry] NO presenter matched sid={} askId={} " +
                        "(user will not see the question until timeout)",
                evt.sessionId(), evt.question().askId());
    }
}
