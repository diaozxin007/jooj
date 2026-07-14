package com.xilidou.jooj.channel;

import com.xilidou.jooj.agent.control.ClarifyQuestion;
import com.xilidou.jooj.agent.control.PendingQuestion;
import com.xilidou.jooj.agent.control.PermissionQuestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * s22 D-12-c:微信 (IM 类) channel 的 {@link AnswerPresenter} 实现 —— 把 pending question
 * 格式化成文本消息投递到用户微信,替代 web 的弹框。
 *
 * <h3>触发条件</h3>
 *
 * <ol>
 *   <li>{@link PresenterRegistry} 收到 {@link com.xilidou.jooj.agent.PendingQuestionRegistered}</li>
 *   <li>调 {@link #supports}:sid 前缀 {@code chat_weixin_} 或 question.originChannel==="weixin"</li>
 *   <li>调 {@link #present}:格式化文本 → {@link ChannelDeliverer#deliver}("weixin", peerId, text)</li>
 * </ol>
 *
 * <h3>文本格式</h3>
 *
 * <pre>
 *   🤖 需要您做几个选择:
 *
 *   【1/2 · 技术栈】
 *   希望使用哪种技术栈?
 *     A. Java — Spring Boot / Maven
 *     B. TypeScript — Node.js
 *     C. Python — FastAPI / Flask
 *     D. Go — Gin / Echo
 *     E. 其它(请填写)
 *
 *   【2/2 · 部署方式 · 可多选】
 *   ...
 *
 *   💡 回复:
 *     单题: "A" 或 "E: Ruby on Rails"(自定义)
 *     多题: "1A 2B"(第1题选A,第2题选B)
 *     多选题: "1AB"(该题同时选A和B)
 *     3 分钟内未回复将取消。
 * </pre>
 *
 * <h3>Permission 场景</h3>
 *
 * <p>Permission ASK 在 IM 里语义特殊 —— 通常是"允许 tool 跑 rm -rf?"。
 * 目前简单文本化,让用户回 A/B(允许/拒绝)。未来可以专门定制。
 */
@Component
@Slf4j
public class WeixinAnswerPresenter implements AnswerPresenter {

    /**
     * sid 命名约定:{@link com.xilidou.jooj.channel.InboundDispatcher#sessionIdFor} 生成
     * {@code chat_weixin_<sanitized-peer>} 格式。用它判断是否本 presenter 处理。
     */
    public static final String WEIXIN_SID_PREFIX = "chat_weixin_";
    public static final String WEIXIN_CHANNEL = "weixin";

    /** ObjectProvider 可选依赖:jooj 无 weixin 模块启用时不注入 deliverer,本 presenter 静默 */
    private final ObjectProvider<ChannelDeliverer> delivererProvider;

    public WeixinAnswerPresenter(ObjectProvider<ChannelDeliverer> delivererProvider) {
        this.delivererProvider = delivererProvider;
    }

    @Override
    public boolean supports(String sessionId, PendingQuestion question) {
        if (question == null) return false;
        // 优先看 question.originChannel(权威)
        String ch = question.originChannel();
        if (ch != null) return WEIXIN_CHANNEL.equals(ch);
        // fallback 按 sid 前缀
        return sessionId != null && sessionId.startsWith(WEIXIN_SID_PREFIX);
    }

    @Override
    public void present(String sessionId, PendingQuestion question) {
        // 拿 peerId:question 明确带 originPeerId 时用它,否则**目前无法反查**
        // (sid 里的 peer 是 sanitized 后的,不可逆)—— 只能靠 question 明确传
        String peerId = question.originPeerId();
        if (peerId == null || peerId.isBlank()) {
            log.warn("[WeixinPresenter] sid={} askId={} 无 originPeerId,无法投递",
                    sessionId, question.askId());
            return;
        }

        ChannelDeliverer deliverer = delivererProvider.getIfAvailable();
        if (deliverer == null) {
            log.warn("[WeixinPresenter] no ChannelDeliverer available; sid={} askId={} skipped",
                    sessionId, question.askId());
            return;
        }

        String text = formatText(question);
        try {
            boolean ok = deliverer.deliver(WEIXIN_CHANNEL, peerId, text);
            log.info("[WeixinPresenter] delivered sid={} askId={} peer={} ok={}",
                    sessionId, question.askId(), peerId, ok);
        } catch (Throwable t) {
            log.warn("[WeixinPresenter] deliver failed sid={} askId={} peer={}: {}",
                    sessionId, question.askId(), peerId, t.toString());
        }
    }

    /** 把 question 格式化为可读文本(含回复格式提示)。 */
    String formatText(PendingQuestion question) {
        if (question instanceof ClarifyQuestion cq) {
            return formatClarify(cq);
        }
        if (question instanceof PermissionQuestion pq) {
            return formatPermission(pq);
        }
        return "🤖 [未识别的问题类型: " + question.type() + "]";
    }

    private String formatClarify(ClarifyQuestion cq) {
        StringBuilder sb = new StringBuilder();
        int total = cq.questions().size();
        sb.append(total == 1 ? "🤖 需要您做一个选择:\n" : "🤖 需要您做几个选择:\n");

        for (int qi = 0; qi < total; qi++) {
            ClarifyQuestion.SubQuestion sq = cq.questions().get(qi);
            sb.append("\n【");
            if (total > 1) sb.append(qi + 1).append("/").append(total).append(" · ");
            sb.append(sq.header());
            if (sq.multiSelect()) sb.append(" · 可多选");
            sb.append("】\n");
            sb.append(sq.question()).append("\n");

            // A/B/C/D 选项
            for (int oi = 0; oi < sq.options().size(); oi++) {
                ClarifyQuestion.Option op = sq.options().get(oi);
                sb.append("  ").append((char) ('A' + oi)).append(". ").append(op.label());
                if (op.description() != null && !op.description().isBlank()) {
                    sb.append(" — ").append(op.description());
                }
                sb.append("\n");
            }
            // Other 位(下一个字母)
            sb.append("  ").append((char) ('A' + sq.options().size())).append(". 其它(请填写)\n");
        }

        sb.append("\n💡 回复格式:\n");
        if (total == 1) {
            sb.append("  单选:如 \"A\" ,自定义:\"E: 具体内容\"\n");
        } else {
            sb.append("  多题:\"1A 2B\"(第1题A,第2题B)\n");
            sb.append("  自定义:\"1E: 具体内容\"\n");
        }
        sb.append("  3 分钟内未回复将取消");
        return sb.toString();
    }

    private String formatPermission(PermissionQuestion pq) {
        return "⚠️ 需要您批准工具调用:\n" +
                "\n工具: " + pq.toolName() +
                "\n参数: " + pq.toolInput() +
                "\n原因: " + pq.reason() +
                "\n\n回复:\n" +
                "  A. 允许\n" +
                "  B. 拒绝\n" +
                "  3 分钟内未回复将拒绝";
    }
}
