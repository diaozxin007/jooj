package com.xilidou.jooj.channel;

import com.xilidou.jooj.JoojTestConfig;
import com.xilidou.jooj.http.MockAnthropicClient;
import com.xilidou.jooj.http.ResponseFixtures;
import com.xilidou.jooj.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InboundDispatcher 集成测试 —— 用 mock channel 模拟入站 + 验证回写。
 *
 * <h3>覆盖场景</h3>
 *
 * <ul>
 *   <li>auto-create session("chat:weixin:peerA")</li>
 *   <li>LLM 文本回复回写到 channel.sendOutbound</li>
 *   <li>session 路由:不同 peer 走不同 session,history 不串</li>
 *   <li>channel 没注册 → 入站走 LLM 但不回写,不抛异常</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(JoojTestConfig.class)
class InboundDispatcherTest {

    @Autowired InboundDispatcher dispatcher;
    @Autowired SessionService sessionService;
    @Autowired MockAnthropicClient mock;

    private FakeChannel fake;

    @BeforeEach
    void setUp() {
        // 清掉之前测试可能创建的 session,确保独立
        for (String sid : List.of("chat_weixin_peerA", "chat_weixin_peerB",
                "chat_weixin_peerC", "chat_weixin_peerD")) {
            if (sessionService.exists(sid)) {
                sessionService.clearHistory(sid);
            }
        }
        fake = new FakeChannel();
        dispatcher.registerChannel(fake);
    }

    @Test
    @DisplayName("inbound 文本 → 自动建 session → LLM 回复 → 回写 channel")
    void inbound_text_round_trip() {
        // 1 LLM + 1 memory consolidator
        mock.reset(
                ResponseFixtures.endTurn("hello back"),
                ResponseFixtures.endTurn("[]")
        );

        ChannelMessage msg = new ChannelMessage(
                "weixin", "peerA", "Alice", "hi there", "msg-001");
        dispatcher.dispatch(msg);

        // session 自动建好了(注意 sanitize:冒号 → 下划线)
        assertTrue(sessionService.exists("chat_weixin_peerA"),
                "应自动创建 session chat_weixin_peerA");

        // channel 收到回写
        assertEquals(1, fake.outbound.size(), "应回写一次");
        assertEquals("peerA", fake.outbound.get(0).peer);
        assertEquals("hello back", fake.outbound.get(0).text);

        // history 里有 user + assistant
        var history = sessionService.loadHistory("chat_weixin_peerA");
        assertEquals(2, history.size(), "应有 user + assistant 两条");
    }

    @Test
    @DisplayName("不同 peer 走不同 session,上下文不串")
    void per_peer_session_isolation() {
        // 每次 dispatch = 1 LLM + 1 memory consolidator,2 次共 4 个响应。memory 收到非
        // JSON 视为无 memory,不影响。
        mock.reset(
                ResponseFixtures.endTurn("reply to A"),
                ResponseFixtures.endTurn("[]"),
                ResponseFixtures.endTurn("reply to B"),
                ResponseFixtures.endTurn("[]")
        );

        dispatcher.dispatch(new ChannelMessage("weixin", "peerA", null, "hi from A", null));
        dispatcher.dispatch(new ChannelMessage("weixin", "peerB", null, "hi from B", null));

        assertEquals(2, sessionService.loadHistory("chat_weixin_peerA").size());
        assertEquals(2, sessionService.loadHistory("chat_weixin_peerB").size());
        assertEquals(2, fake.outbound.size());
        assertEquals("peerA", fake.outbound.get(0).peer);
        assertEquals("reply to A", fake.outbound.get(0).text);
        assertEquals("peerB", fake.outbound.get(1).peer);
        assertEquals("reply to B", fake.outbound.get(1).text);
    }

    @Test
    @DisplayName("channel 没注册 → 不抛 + LLM 仍跑")
    void unregistered_channel_no_throw() {
        mock.reset(
                ResponseFixtures.endTurn("ack"),
                ResponseFixtures.endTurn("[]")
        );
        dispatcher.unregisterChannel("weixin");

        assertDoesNotThrow(() -> dispatcher.dispatch(
                new ChannelMessage("weixin", "peerC", null, "hi", null)));

        // history 仍写入(LLM 跑了)
        assertTrue(sessionService.exists("chat_weixin_peerC"));
        assertEquals(0, fake.outbound.size(), "channel 没注册,不能回写");
    }

    @Test
    @DisplayName("LLM 没文本回复(只 tool_use 没 text)→ 不回写不报错")
    void no_text_reply_skipped() {
        // assistant 直接 end_turn 但 content 为空
        mock.reset(
                ResponseFixtures.endTurn(""),
                ResponseFixtures.endTurn("[]")
        );

        dispatcher.dispatch(new ChannelMessage("weixin", "peerD", null, "hi", null));

        // 不应回写
        assertEquals(0, fake.outbound.size(),
                "LLM 没文本时不应触发 sendOutbound");
    }

    @Test
    @DisplayName("非法字符 peerId(中文/邮箱)→ sanitize 成下划线")
    void session_id_sanitized() {
        assertEquals("chat_weixin_user_company_com",
                InboundDispatcher.sessionIdFor(
                        new ChannelMessage("weixin", "user@company.com", null, "x", null)));
        // 中文 4 个字 + ID 两个 ASCII = 用户中文ID 共 6 字符,非 ASCII 的 4 个字各替换 → 4 个 _
        assertEquals("chat_weixin_____ID",
                InboundDispatcher.sessionIdFor(
                        new ChannelMessage("weixin", "用户中文ID", null, "x", null)));
    }

    /** 简易 channel 实现:把出站记录到 list,测试用。 */
    static class FakeChannel implements MessageChannel {
        record Out(String peer, String text) {}
        final List<Out> outbound = new ArrayList<>();

        @Override public String name() { return "weixin"; }
        @Override public void start(InboundDispatcher d) {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return true; }
        @Override public void sendOutbound(String peerId, String text) {
            outbound.add(new Out(peerId, text));
        }
    }
}
