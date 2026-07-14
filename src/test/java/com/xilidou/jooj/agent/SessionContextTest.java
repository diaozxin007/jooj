package com.xilidou.jooj.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * s22 D-10-C:{@link SessionContext} 单测。核心 6 场景 —— push/pop 匹配、
 * 线程隔离、嵌套、null 处理。
 */
class SessionContextTest {

    @AfterEach
    void cleanupAny() {
        // 保险清理,防止测试之间泄漏
        SessionContext.pop(null);
        SessionContext.popChannel(null);
    }

    @Test
    @DisplayName("push → current 拿到,pop 后 current=null")
    void push_then_pop() {
        assertNull(SessionContext.current());
        String prev = SessionContext.push("sid-1");
        assertNull(prev, "首次 push 返回值应为 null(之前无 sid)");
        assertEquals("sid-1", SessionContext.current());
        assertTrue(SessionContext.isBound());

        SessionContext.pop(prev);
        assertNull(SessionContext.current());
        assertFalse(SessionContext.isBound());
    }

    @Test
    @DisplayName("嵌套 push:pop 精确恢复到上一层")
    void nested_push_pop() {
        String prev1 = SessionContext.push("outer");
        assertNull(prev1);
        assertEquals("outer", SessionContext.current());

        String prev2 = SessionContext.push("inner");
        assertEquals("outer", prev2, "嵌套 push 应返回外层 sid");
        assertEquals("inner", SessionContext.current());

        SessionContext.pop(prev2);
        assertEquals("outer", SessionContext.current(), "pop 后应恢复外层");

        SessionContext.pop(prev1);
        assertNull(SessionContext.current());
    }

    @Test
    @DisplayName("线程隔离:另一线程的 push 不影响本线程")
    void thread_isolation() throws Exception {
        SessionContext.push("main-sid");
        Thread other = new Thread(() -> {
            assertNull(SessionContext.current(), "新线程应无绑定");
            SessionContext.push("other-sid");
            assertEquals("other-sid", SessionContext.current());
        });
        other.start();
        other.join();

        assertEquals("main-sid", SessionContext.current(),
                "本线程 sid 不该被另一线程改动");
        SessionContext.pop(null);
    }

    @Test
    @DisplayName("null / blank 语义:isBound=false")
    void null_or_blank() {
        SessionContext.push(null);
        assertNull(SessionContext.current());
        assertFalse(SessionContext.isBound());

        SessionContext.push("");
        assertEquals("", SessionContext.current());
        assertFalse(SessionContext.isBound(), "空字符串不算 bound");

        SessionContext.push("  ");
        assertFalse(SessionContext.isBound(), "空白字符串不算 bound");
    }

    @Test
    @DisplayName("pop(null) 清空 ThreadLocal 而非留空 entry")
    void pop_null_removes() {
        SessionContext.push("sid");
        SessionContext.pop(null);
        // 无法直接观察 ThreadLocal map,但下次 current 是 null 就够了
        assertNull(SessionContext.current());
    }

    @Test
    @DisplayName("典型模式:try/finally 严格恢复")
    void typical_pattern() {
        assertNull(SessionContext.current());
        String prev = SessionContext.push("sid");
        try {
            assertEquals("sid", SessionContext.current());
            // 模拟异常抛出
            throw new RuntimeException("boom");
        } catch (RuntimeException ignore) {
            // just catching
        } finally {
            SessionContext.pop(prev);
        }
        assertNull(SessionContext.current(), "即使异常也要恢复");
    }

    // ---- s22 D-12:channel/peerId push/pop ----

    @Test
    @DisplayName("D-12: pushChannel 绑 channel + peerId,current* 能读到")
    void channel_push_reads() {
        assertNull(SessionContext.currentChannel());
        assertNull(SessionContext.currentPeerId());
        SessionContext.ChannelPeer prev = SessionContext.pushChannel("weixin", "wxid_abc");
        try {
            assertEquals("weixin", SessionContext.currentChannel());
            assertEquals("wxid_abc", SessionContext.currentPeerId());
            assertNull(prev.channel(), "第一次 push 前是 null");
            assertNull(prev.peerId());
        } finally {
            SessionContext.popChannel(prev);
        }
        assertNull(SessionContext.currentChannel(), "pop 后清空");
        assertNull(SessionContext.currentPeerId());
    }

    @Test
    @DisplayName("D-12: 嵌套 push 精确恢复(外层 web,内层 weixin)")
    void channel_nested_restore() {
        SessionContext.ChannelPeer outer = SessionContext.pushChannel("web", "u1");
        try {
            assertEquals("web", SessionContext.currentChannel());
            SessionContext.ChannelPeer inner = SessionContext.pushChannel("weixin", "wx2");
            try {
                assertEquals("weixin", SessionContext.currentChannel());
                assertEquals("wx2", SessionContext.currentPeerId());
                assertEquals("web", inner.channel(), "inner 保存 outer 快照");
                assertEquals("u1", inner.peerId());
            } finally {
                SessionContext.popChannel(inner);
            }
            assertEquals("web", SessionContext.currentChannel(), "pop 内层后回到外层");
            assertEquals("u1", SessionContext.currentPeerId());
        } finally {
            SessionContext.popChannel(outer);
        }
        assertNull(SessionContext.currentChannel(), "全部 pop 后清空");
    }

    @Test
    @DisplayName("D-12: pushChannel 允许 null(web / CLI 场景 —— 只 push sid 不 push channel)")
    void channel_null_ok() {
        SessionContext.ChannelPeer prev = SessionContext.pushChannel(null, null);
        try {
            assertNull(SessionContext.currentChannel());
            assertNull(SessionContext.currentPeerId());
        } finally {
            SessionContext.popChannel(prev);
        }
        assertNull(SessionContext.currentChannel());
    }
}
