package com.xilidou.jooj.channel;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xilidou.jooj.agent.control.ClarifyQuestion;
import com.xilidou.jooj.agent.control.PermissionQuestion;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * s22 D-12-c:{@link WeixinAnswerPresenter} 单测。
 */
class WeixinAnswerPresenterTest {

    /** 简单 ObjectProvider 实现,供测试注入 deliverer / 无 deliverer 两种场景。 */
    static class StubDelivererProvider implements ObjectProvider<ChannelDeliverer> {
        private final ChannelDeliverer d;
        StubDelivererProvider(ChannelDeliverer d) { this.d = d; }
        @Override public ChannelDeliverer getIfAvailable() { return d; }
        @Override public ChannelDeliverer getObject() { return d; }
        @Override public ChannelDeliverer getObject(Object... args) { return d; }
        @Override public ChannelDeliverer getIfUnique() { return d; }
    }

    @Test
    @DisplayName("supports:sid 前缀 chat_weixin_ → true;别的 → false")
    void supports_by_sid_prefix() {
        var p = new WeixinAnswerPresenter(new StubDelivererProvider(null));
        var q = ClarifyQuestion.of(List.of(sub()));
        assertTrue(p.supports("chat_weixin_wxid_abc", q));
        assertFalse(p.supports("default", q));
        assertFalse(p.supports("chat_web_xxx", q));
    }

    @Test
    @DisplayName("supports:question.originChannel=weixin 权威优先 → true 即便 sid 不像微信")
    void supports_by_origin_channel() {
        var p = new WeixinAnswerPresenter(new StubDelivererProvider(null));
        var q = ClarifyQuestion.of(List.of(sub()), "weixin", "wxid_xxx");
        assertTrue(p.supports("random_sid", q));

        var qWeb = ClarifyQuestion.of(List.of(sub()), "web", null);
        assertFalse(p.supports("chat_weixin_xxx", qWeb));
    }

    @Test
    @DisplayName("formatText clarify:含所有 sub-question + A-D + Other + 提示")
    void format_text_clarify() {
        var p = new WeixinAnswerPresenter(new StubDelivererProvider(null));
        var q = ClarifyQuestion.of(List.of(
                new ClarifyQuestion.SubQuestion("哪个 UI?", "UI",
                        List.of(
                                new ClarifyQuestion.Option("React", "生态最大"),
                                new ClarifyQuestion.Option("Vue", null)),
                        false)));

        String text = p.formatText(q);
        assertTrue(text.contains("需要您做一个选择"));
        assertTrue(text.contains("【UI】"));
        assertTrue(text.contains("哪个 UI?"));
        assertTrue(text.contains("A. React — 生态最大"));
        assertTrue(text.contains("B. Vue"));
        assertTrue(text.contains("C. 其它(请填写)"), "Other 位应为 A+options.size(); options=2 → C. 其它");
        assertTrue(text.contains("回复格式"));
    }

    @Test
    @DisplayName("formatText clarify multi:显示'可多选'")
    void format_text_multi() {
        var p = new WeixinAnswerPresenter(new StubDelivererProvider(null));
        var q = ClarifyQuestion.of(List.of(
                new ClarifyQuestion.SubQuestion("features?", "feat",
                        List.of(
                                new ClarifyQuestion.Option("auth", null),
                                new ClarifyQuestion.Option("search", null)),
                        true)));
        String text = p.formatText(q);
        assertTrue(text.contains("· 可多选"));
    }

    @Test
    @DisplayName("formatText permission:显示 tool/参数/原因 + A允许 B拒绝")
    void format_text_permission() {
        var p = new WeixinAnswerPresenter(new StubDelivererProvider(null));
        var toolUse = new ToolUseBlock("toolu_1", "bash",
                JsonNodeFactory.instance.objectNode().put("command", "rm -rf build"));
        var pq = PermissionQuestion.of(toolUse, "matched destructive", "weixin", "wxid_xxx");
        String text = p.formatText(pq);
        assertTrue(text.contains("需要您批准工具调用"));
        assertTrue(text.contains("bash"));
        assertTrue(text.contains("rm -rf build"));
        assertTrue(text.contains("matched destructive"));
        assertTrue(text.contains("A. 允许"));
        assertTrue(text.contains("B. 拒绝"));
    }

    @Test
    @DisplayName("present:调 deliverer.deliver 传对参数")
    void present_calls_deliverer() {
        AtomicReference<String> capturedChannel = new AtomicReference<>();
        AtomicReference<String> capturedPeer = new AtomicReference<>();
        AtomicReference<String> capturedText = new AtomicReference<>();
        ChannelDeliverer stub = (channel, peer, text) -> {
            capturedChannel.set(channel);
            capturedPeer.set(peer);
            capturedText.set(text);
            return true;
        };
        var p = new WeixinAnswerPresenter(new StubDelivererProvider(stub));
        var q = ClarifyQuestion.of(List.of(sub()), "weixin", "wxid_xxx");

        p.present("chat_weixin_xxx", q);

        assertEquals("weixin", capturedChannel.get());
        assertEquals("wxid_xxx", capturedPeer.get());
        assertNotNull(capturedText.get());
        assertTrue(capturedText.get().contains("需要您做"));
    }

    @Test
    @DisplayName("present:originPeerId 空时 warn 但不抛,不调 deliverer")
    void present_missing_peer_id_skips() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        ChannelDeliverer stub = (c, p, t) -> { called.set(true); return true; };
        var pres = new WeixinAnswerPresenter(new StubDelivererProvider(stub));
        var q = ClarifyQuestion.of(List.of(sub()), "weixin", null);

        pres.present("chat_weixin_xxx", q);   // 不抛
        assertFalse(called.get(), "无 peerId 不该调 deliverer");
    }

    @Test
    @DisplayName("present:deliverer 不可用(ObjectProvider 空)时静默 no-op")
    void present_no_deliverer_available_no_op() {
        var pres = new WeixinAnswerPresenter(new StubDelivererProvider(null));
        var q = ClarifyQuestion.of(List.of(sub()), "weixin", "wxid_xxx");
        pres.present("chat_weixin_xxx", q);   // 不抛
    }

    // ── 辅助 ─────────────────────────────────────────────────

    private ClarifyQuestion.SubQuestion sub() {
        return new ClarifyQuestion.SubQuestion("q?", "hdr",
                List.of(
                        new ClarifyQuestion.Option("A", null),
                        new ClarifyQuestion.Option("B", null)),
                false);
    }
}
