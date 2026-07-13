package com.xilidou.jooj.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InterruptRegistry} 单元测试 —— s22 D-8 核心机制的最小单位验证。
 *
 * <p>覆盖 5 个场景:
 * <ol>
 *   <li>初始状态无请求</li>
 *   <li>request → isRequested / consumeIfRequested 语义</li>
 *   <li>consumeIfRequested 消费后清除</li>
 *   <li>request 幂等(重复调不影响)</li>
 *   <li>clear 清除待请求</li>
 *   <li>null / blank sessionId 防御</li>
 * </ol>
 */
class InterruptRegistryTest {

    @Test
    @DisplayName("初始状态:任何 sessionId 都未被请求打断")
    void initial_state_empty() {
        var reg = new InterruptRegistry();
        assertFalse(reg.isRequested("sid1"));
        assertFalse(reg.consumeIfRequested("sid1"));
        assertEquals(0, reg.pendingCount());
    }

    @Test
    @DisplayName("request → isRequested true;consumeIfRequested 首次 true 之后消费清除")
    void request_then_consume() {
        var reg = new InterruptRegistry();
        assertTrue(reg.request("sid1"), "首次 request 返回 true");
        assertTrue(reg.isRequested("sid1"));
        assertEquals(1, reg.pendingCount());

        // consume 消费并清除
        assertTrue(reg.consumeIfRequested("sid1"), "consume 首次返回 true");
        assertFalse(reg.isRequested("sid1"), "consume 后 isRequested 应转 false");
        assertFalse(reg.consumeIfRequested("sid1"), "第二次 consume 已无请求应返回 false");
        assertEquals(0, reg.pendingCount());
    }

    @Test
    @DisplayName("request 幂等:重复调返回 false,pending 数量不变")
    void request_idempotent() {
        var reg = new InterruptRegistry();
        assertTrue(reg.request("sid1"));
        assertFalse(reg.request("sid1"), "重复 request 应返回 false");
        assertFalse(reg.request("sid1"));
        assertEquals(1, reg.pendingCount(), "重复 request 不叠加");
    }

    @Test
    @DisplayName("多 session 隔离:consume A 不影响 B")
    void multi_session_isolated() {
        var reg = new InterruptRegistry();
        reg.request("A");
        reg.request("B");
        assertEquals(2, reg.pendingCount());

        assertTrue(reg.consumeIfRequested("A"));
        assertTrue(reg.isRequested("B"), "consume A 不该影响 B");
        assertEquals(1, reg.pendingCount());
    }

    @Test
    @DisplayName("clear:主动清除某 session 的挂起请求(session 删除场景)")
    void clear_removes_pending() {
        var reg = new InterruptRegistry();
        reg.request("sid1");
        reg.clear("sid1");
        assertFalse(reg.isRequested("sid1"));
        assertFalse(reg.consumeIfRequested("sid1"));
    }

    @Test
    @DisplayName("null / blank sessionId 防御:不抛异常,返回 false")
    void null_or_blank_sessionId_defensive() {
        var reg = new InterruptRegistry();
        assertFalse(reg.request(null));
        assertFalse(reg.request(""));
        assertFalse(reg.request("  "));
        assertFalse(reg.isRequested(null));
        assertFalse(reg.consumeIfRequested(null));
        assertEquals(0, reg.pendingCount());
    }
}
