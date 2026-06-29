package com.xilidou.jooj.agent;

import com.xilidou.jooj.tool.ToolResult;
import com.xilidou.jooj.http.dto.TextBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * s20 Demo 12 回归 —— 锁定 BackgroundTaskManager 跨 session 通知不串味。
 */
class BackgroundTaskManagerPerSessionTest {

    private ExecutorService executor;
    private BackgroundTaskManager mgr;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        mgr = new BackgroundTaskManager(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 简单轮询等待,避免引入 Awaitility 依赖。 */
    private static void waitUntil(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timeout waiting for condition");
    }

    @Test
    @DisplayName("alice session 启动的 bg 任务,bob session drain 时收不到")
    void notifications_isolated_per_session() throws Exception {
        AtomicInteger doneA = new AtomicInteger();
        AtomicInteger doneB = new AtomicInteger();

        mgr.start("alice", "tu_a", "do A", () -> {
            doneA.incrementAndGet();
            return new ToolResult(true, "A finished");
        });
        mgr.start("bob", "tu_b", "do B", () -> {
            doneB.incrementAndGet();
            return new ToolResult(true, "B finished");
        });

        waitUntil(() -> doneA.get() == 1 && doneB.get() == 1, 5000);
        Thread.sleep(80);

        List<TextBlock> bobNotifs = mgr.drainNotifications("bob");
        assertEquals(1, bobNotifs.size(), "bob 只该收到自己的 1 条");
        assertTrue(bobNotifs.get(0).getText().contains("B finished"));
        assertFalse(bobNotifs.get(0).getText().contains("A finished"),
                "bob 不该看到 alice 的 task_notification");

        List<TextBlock> aliceNotifs = mgr.drainNotifications("alice");
        assertEquals(1, aliceNotifs.size(), "alice 该收到自己的 1 条");
        assertTrue(aliceNotifs.get(0).getText().contains("A finished"));

        assertEquals(0, mgr.drainNotifications("alice").size());
        assertEquals(0, mgr.drainNotifications("bob").size());
    }

    @Test
    @DisplayName("老 start(无 sessionId) → 老 drain(无 sessionId) 仍可工作")
    void legacy_no_session_paths_compatible() throws Exception {
        AtomicInteger done = new AtomicInteger();

        mgr.start("tu_old", "do legacy", () -> {
            done.incrementAndGet();
            return new ToolResult(true, "legacy done");
        });

        waitUntil(() -> done.get() == 1, 5000);
        Thread.sleep(80);

        List<TextBlock> notifs = mgr.drainNotifications();
        assertEquals(1, notifs.size());
        assertTrue(notifs.get(0).getText().contains("legacy done"));
    }
}
