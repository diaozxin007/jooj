package com.xilidou.jooj.agent;

import com.xilidou.jooj.agent.control.AllowAnswer;
import com.xilidou.jooj.agent.control.Answer;
import com.xilidou.jooj.agent.control.AskTimeoutException;
import com.xilidou.jooj.agent.control.DenyAnswer;
import com.xilidou.jooj.agent.control.PermissionQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

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

    // ─────────────────────────────────────────────────────────────
    //  s22 D-10-B:ask / answer / timeout / cancel 契约
    // ─────────────────────────────────────────────────────────────

    private static PermissionQuestion sampleQuestion() {
        return new PermissionQuestion(
                "askId-1",
                Instant.parse("2026-07-13T12:00:00Z"),
                "bash",
                "{cmd: rm -rf}",
                "matched destructive pattern",
                null, null);
    }

    @Test
    @DisplayName("D-10-B ask + answer round-trip:REST 线程 answer → agent 线程收到 Answer")
    void ask_and_answer_round_trip() throws Exception {
        var ctl = new DefaultAgentControl();
        var q = sampleQuestion();

        AtomicReference<Answer> received = new AtomicReference<>();
        CompletableFuture<Void> agentThread = CompletableFuture.runAsync(() -> {
            try {
                Answer a = ctl.ask("sid", q, Duration.ofSeconds(5));
                received.set(a);
            } catch (Exception e) {
                fail("agent thread threw: " + e);
            }
        });

        // 等一下让 agent 线程真进 ask()
        waitUntil(() -> ctl.pendingAskCount("sid") == 1, Duration.ofSeconds(1));
        assertEquals(1, ctl.pendingAskCount("sid"));
        assertEquals(1, ctl.listPending("sid").size());

        // REST 线程 answer
        boolean ok = ctl.answer("sid", q.askId(), AllowAnswer.INSTANCE);
        assertTrue(ok);

        agentThread.get(2, TimeUnit.SECONDS);   // 等 agent 完成
        assertTrue(received.get() instanceof AllowAnswer);
        assertEquals(0, ctl.pendingAskCount("sid"), "answer 后 pending 应清零");
    }

    @Test
    @DisplayName("D-10-B ask timeout:超过 duration 未 answer → 抛 AskTimeoutException,pending 清零")
    void ask_timeout() {
        var ctl = new DefaultAgentControl();
        var q = sampleQuestion();

        long start = System.currentTimeMillis();
        AskTimeoutException aie = assertThrows(AskTimeoutException.class,
                () -> ctl.ask("sid", q, Duration.ofMillis(200)));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(q.askId(), aie.getAskId());
        assertTrue(elapsed >= 200, "至少等 200ms 才 timeout,实际 " + elapsed);
        assertTrue(elapsed < 1000, "不该等太久,实际 " + elapsed);
        assertEquals(0, ctl.pendingAskCount("sid"), "timeout 后 pending 应清零");
    }

    @Test
    @DisplayName("D-10-B cancelPending:清除所有挂起 ask,让 agent 线程抛 AgentInterruptedException")
    void cancel_pending_wakes_agents() throws Exception {
        var ctl = new DefaultAgentControl();
        var q = sampleQuestion();

        AtomicReference<Throwable> caught = new AtomicReference<>();
        CompletableFuture<Void> agentThread = CompletableFuture.runAsync(() -> {
            try {
                ctl.ask("sid", q, Duration.ofSeconds(30));
                fail("应该被 cancel,不该正常返回");
            } catch (Throwable t) {
                caught.set(t);
            }
        });

        waitUntil(() -> ctl.pendingAskCount("sid") == 1, Duration.ofSeconds(1));

        int cancelled = ctl.cancelPending("sid");
        assertEquals(1, cancelled);

        agentThread.get(2, TimeUnit.SECONDS);
        assertTrue(caught.get() instanceof AgentInterruptedException,
                "应抛 AgentInterruptedException,实际:" + caught.get());
    }

    @Test
    @DisplayName("D-10-B interrupt 到达时自动 cancel 所有 pending ask")
    void interrupt_cancels_pending_asks() throws Exception {
        var ctl = new DefaultAgentControl();
        var q = sampleQuestion();

        AtomicReference<Throwable> caught = new AtomicReference<>();
        CompletableFuture<Void> agentThread = CompletableFuture.runAsync(() -> {
            try {
                ctl.ask("sid", q, Duration.ofSeconds(30));
            } catch (Throwable t) {
                caught.set(t);
            }
        });

        waitUntil(() -> ctl.pendingAskCount("sid") == 1, Duration.ofSeconds(1));

        // request interrupt 应该内部 cancelPending → agent 抛 AgentInterruptedException
        assertTrue(ctl.requestInterrupt("sid"));

        agentThread.get(2, TimeUnit.SECONDS);
        assertTrue(caught.get() instanceof AgentInterruptedException,
                "interrupt 期间挂起的 ask 应立即抛异常,而不用等 timeout;实际:" + caught.get());
        assertEquals(0, ctl.pendingAskCount("sid"));
    }

    @Test
    @DisplayName("D-10-B answer 不存在的 askId 返 false(REST 层给 404)")
    void answer_missing_askid_returns_false() {
        var ctl = new DefaultAgentControl();
        boolean ok = ctl.answer("sid", "nonexistent-askid", AllowAnswer.INSTANCE);
        assertFalse(ok);
    }

    @Test
    @DisplayName("D-10-B ask 前 sid 已在 interrupt 状态 → 直接抛 AgentInterruptedException(不挂起)")
    void ask_fast_fail_when_already_interrupted() {
        var ctl = new DefaultAgentControl();
        ctl.requestInterrupt("sid");
        assertThrows(AgentInterruptedException.class,
                () -> ctl.ask("sid", sampleQuestion(), Duration.ofSeconds(30)));
        assertEquals(0, ctl.pendingAskCount("sid"), "fast-fail 路径不该占位");
    }

    @Test
    @DisplayName("D-10-B ask 参数校验:null / 非正 timeout → IAE")
    void ask_parameter_validation() {
        var ctl = new DefaultAgentControl();
        var q = sampleQuestion();
        assertThrows(IllegalArgumentException.class,
                () -> ctl.ask(null, q, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> ctl.ask("sid", null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> ctl.ask("sid", q, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> ctl.ask("sid", q, Duration.ofSeconds(-1)));
    }

    @Test
    @DisplayName("D-10-B listPending 隔离:sid A 挂起不影响 sid B")
    void list_pending_isolated_by_session() throws Exception {
        var ctl = new DefaultAgentControl();
        var qA = new PermissionQuestion("askA", Instant.now(), "bash", "{}", "test", null, null);

        CompletableFuture.runAsync(() -> {
            try { ctl.ask("A", qA, Duration.ofSeconds(5)); } catch (Exception ignore) {}
        });
        waitUntil(() -> ctl.pendingAskCount("A") == 1, Duration.ofSeconds(1));

        assertEquals(1, ctl.listPending("A").size());
        assertEquals(0, ctl.listPending("B").size(), "sid B 不该看到 A 的 pending");
        assertTrue(ctl.findPending("A", "askA").isPresent());
        assertTrue(ctl.findPending("B", "askA").isEmpty(), "跨 sid 查询不该匹配");

        ctl.cancelPending("A");   // 清理测试
    }

    // ── 辅助 ─────────────────────────────────────────────────

    /** 忙等条件成立,防止 sleep 靠时间估;超时 fail. */
    private static void waitUntil(java.util.function.BooleanSupplier cond, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(10); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
        }
        fail("condition not satisfied within " + timeout);
    }
}
