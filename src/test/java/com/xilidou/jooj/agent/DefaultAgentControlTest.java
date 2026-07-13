package com.xilidou.jooj.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultAgentControl} 单元测试 —— s22 D-10-A(原 D-8 InterruptRegistryTest 迁过来)。
 *
 * <p>只覆盖 signal 部分(interrupt),ask 部分留给 D-10-B 加。
 *
 * <p>行为契约:
 * <ol>
 *   <li>初始状态无请求</li>
 *   <li>request → isRequested / consume 语义</li>
 *   <li>consumeInterrupt 消费后清除</li>
 *   <li>request 幂等(重复调不影响)</li>
 *   <li>clearInterrupt 清除待请求</li>
 *   <li>null / blank sessionId 防御</li>
 * </ol>
 */
class DefaultAgentControlTest {

    @Test
    @DisplayName("初始状态:任何 sessionId 都未被请求打断")
    void initial_state_empty() {
        var ctl = new DefaultAgentControl();
        assertFalse(ctl.isInterruptRequested("sid1"));
        assertFalse(ctl.consumeInterrupt("sid1"));
        assertEquals(0, ctl.pendingInterruptCount());
    }

    @Test
    @DisplayName("requestInterrupt → isInterruptRequested true;consume 首次 true 之后消费清除")
    void request_then_consume() {
        var ctl = new DefaultAgentControl();
        assertTrue(ctl.requestInterrupt("sid1"), "首次 request 返回 true");
        assertTrue(ctl.isInterruptRequested("sid1"));
        assertEquals(1, ctl.pendingInterruptCount());

        // consume 消费并清除
        assertTrue(ctl.consumeInterrupt("sid1"), "consume 首次返回 true");
        assertFalse(ctl.isInterruptRequested("sid1"), "consume 后 isRequested 应转 false");
        assertFalse(ctl.consumeInterrupt("sid1"), "第二次 consume 已无请求应返回 false");
        assertEquals(0, ctl.pendingInterruptCount());
    }

    @Test
    @DisplayName("requestInterrupt 幂等:重复调返回 false,pending 数量不变")
    void request_idempotent() {
        var ctl = new DefaultAgentControl();
        assertTrue(ctl.requestInterrupt("sid1"));
        assertFalse(ctl.requestInterrupt("sid1"), "重复 request 应返回 false");
        assertFalse(ctl.requestInterrupt("sid1"));
        assertEquals(1, ctl.pendingInterruptCount(), "重复 request 不叠加");
    }

    @Test
    @DisplayName("多 session 隔离:consume A 不影响 B")
    void multi_session_isolated() {
        var ctl = new DefaultAgentControl();
        ctl.requestInterrupt("A");
        ctl.requestInterrupt("B");
        assertEquals(2, ctl.pendingInterruptCount());

        assertTrue(ctl.consumeInterrupt("A"));
        assertTrue(ctl.isInterruptRequested("B"), "consume A 不该影响 B");
        assertEquals(1, ctl.pendingInterruptCount());
    }

    @Test
    @DisplayName("clearInterrupt:主动清除某 session 的挂起请求(session 删除场景)")
    void clear_removes_pending() {
        var ctl = new DefaultAgentControl();
        ctl.requestInterrupt("sid1");
        ctl.clearInterrupt("sid1");
        assertFalse(ctl.isInterruptRequested("sid1"));
        assertFalse(ctl.consumeInterrupt("sid1"));
    }

    @Test
    @DisplayName("null / blank sessionId 防御:不抛异常,返回 false")
    void null_or_blank_sessionId_defensive() {
        var ctl = new DefaultAgentControl();
        assertFalse(ctl.requestInterrupt(null));
        assertFalse(ctl.requestInterrupt(""));
        assertFalse(ctl.requestInterrupt("  "));
        assertFalse(ctl.isInterruptRequested(null));
        assertFalse(ctl.consumeInterrupt(null));
        assertEquals(0, ctl.pendingInterruptCount());
    }
}
