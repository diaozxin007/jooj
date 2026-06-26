package com.xilidou.marvis.agent;

import com.xilidou.marvis.http.dto.TextBlock;
import com.xilidou.marvis.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link BackgroundTaskManager} 的核心行为。
 *
 * <p>纯单测,不走 Spring 容器。daemon thread 完成用 {@link CountDownLatch} 等待,
 * 不用 {@code Thread.sleep} 死等(慢且脆)。
 */
class BackgroundTaskManagerTest {

    private BackgroundTaskManager mgr;
    private java.util.concurrent.ExecutorService executor;

    @BeforeEach
    void setUp() {
        // 测试用 cached pool,几乎无限 thread,模拟生产 worker pool 行为
        executor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        mgr = new BackgroundTaskManager(executor);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // ─────────────────────────────────────────────────────────────
    //  start / drain 基础路径
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("start 返回 bg_id 形如 bg_0001,递增分配")
    void start_returns_sequential_bg_id() {
        String id1 = mgr.start("tu_1", "echo a", () -> new ToolResult(true, "a"));
        String id2 = mgr.start("tu_2", "echo b", () -> new ToolResult(true, "b"));
        assertEquals("bg_0001", id1, "首个 id 应为 bg_0001");
        assertEquals("bg_0002", id2);
    }

    @Test
    @DisplayName("start 后立即返回(< 200ms),work 在 daemon thread 跑")
    void start_returns_immediately_while_work_runs_in_daemon() throws Exception {
        CountDownLatch workStarted = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);

        long t0 = System.nanoTime();
        mgr.start("tu_1", "slow", () -> {
            workStarted.countDown();
            try {
                unblock.await(2, TimeUnit.SECONDS); // 阻塞,模拟慢操作
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return new ToolResult(true, "done");
        });
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 200,
                "start 应立即返回(daemon thread 跑 work),实际耗时 " + elapsedMs + "ms");
        assertTrue(workStarted.await(1, TimeUnit.SECONDS),
                "work 应在后台 thread 已开始执行");
        unblock.countDown();
    }

    @Test
    @DisplayName("work 完成后 drainNotifications 返回 1 条 <task_notification> 文本块")
    void completed_work_drains_one_task_notification() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        mgr.start("tu_1", "echo hi", () -> {
            done.countDown();
            return new ToolResult(true, "hi");
        });
        assertTrue(done.await(1, TimeUnit.SECONDS));
        // 等 daemon thread 把结果落到 results map(work 返回到落 map 之间有微秒级窗口)
        waitForResults(1);

        List<TextBlock> notifs = mgr.drainNotifications();
        assertEquals(1, notifs.size(), "应有 1 条通知");
        String text = notifs.get(0).getText();
        assertTrue(text.startsWith("<task_notification"), "格式应是 XML 文本块,实际:" + text);
        assertTrue(text.contains("id=\"bg_0001\""), "应含 bg_0001");
        assertTrue(text.contains("echo hi"), "应含 command");
        assertTrue(text.contains("hi"), "应含 output");
        assertTrue(text.endsWith("</task_notification>"));
    }

    @Test
    @DisplayName("drain 后 tasks/results 被清空(同一个 bg 只能 drain 一次)")
    void drain_clears_state() throws Exception {
        mgr.start("tu_1", "x", () -> new ToolResult(true, "x"));
        waitForResults(1);

        List<TextBlock> first = mgr.drainNotifications();
        assertEquals(1, first.size());

        List<TextBlock> second = mgr.drainNotifications();
        assertTrue(second.isEmpty(), "重复 drain 应返回空");

        assertEquals(0, mgr.taskCount(), "tasks 应被清空");
        assertEquals(0, mgr.pendingResultCount(), "results 应被清空");
    }

    @Test
    @DisplayName("work 抛异常时 status 也变 completed,output 是 'Error: ...'")
    void work_throwing_yields_error_notification() throws Exception {
        mgr.start("tu_1", "boom", () -> {
            throw new RuntimeException("boom!");
        });
        waitForResults(1);

        List<TextBlock> notifs = mgr.drainNotifications();
        assertEquals(1, notifs.size());
        String text = notifs.get(0).getText();
        assertTrue(text.contains("Error: boom!"), "应含错误信息,实际:" + text);
    }

    // ─────────────────────────────────────────────────────────────
    //  启发式 / 决策
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isSlowOperation:bash + 慢操作关键词 → true;bash + 快操作 → false;非 bash → false")
    void slow_operation_heuristic() {
        assertTrue(BackgroundTaskManager.isSlowOperation("bash",
                Map.of("command", "./mvnw test")), "test 关键词命中");
        assertTrue(BackgroundTaskManager.isSlowOperation("bash",
                Map.of("command", "docker build -t img .")), "docker build 命中");
        assertTrue(BackgroundTaskManager.isSlowOperation("bash",
                Map.of("command", "pip install requests")), "pip install 命中");

        assertFalse(BackgroundTaskManager.isSlowOperation("bash",
                Map.of("command", "ls")), "ls 不命中");
        assertFalse(BackgroundTaskManager.isSlowOperation("bash",
                Map.of("command", "echo hi")), "echo 不命中");
        assertFalse(BackgroundTaskManager.isSlowOperation("read_file",
                Map.of("command", "make all")), "非 bash 工具不命中(即使 args 含关键词)");
        assertFalse(BackgroundTaskManager.isSlowOperation("bash",
                Map.of()), "无 command 不命中");
    }

    @Test
    @DisplayName("shouldRunBackground:run_in_background=true 优先;无此参数走启发式")
    void should_run_background_priority() {
        // 显式 true → 即使命令是 ls 也走后台
        assertTrue(BackgroundTaskManager.shouldRunBackground("bash",
                Map.of("command", "ls", "run_in_background", true)));
        // 字符串 "true" 也兼容(LLM 可能给字符串)
        assertTrue(BackgroundTaskManager.shouldRunBackground("bash",
                Map.of("command", "ls", "run_in_background", "true")));
        // 显式 false + 慢命令 → 仍然走启发式...wait,Python 上游是 false 时仍走启发式吗?
        // 看 should_run_background:`return args.get('run_in_background') or is_slow_operation(...)`
        // false 时短路求值会变 is_slow_operation,所以慢命令仍走后台。
        assertTrue(BackgroundTaskManager.shouldRunBackground("bash",
                        Map.of("command", "./mvnw test", "run_in_background", false)),
                "显式 false + 慢命令仍走后台(跟上游一致)");
        // 无 run_in_background + 快命令 → 不走后台
        assertFalse(BackgroundTaskManager.shouldRunBackground("bash",
                Map.of("command", "ls")));
        // 无 args
        assertFalse(BackgroundTaskManager.shouldRunBackground("bash", Map.of()));
    }

    // ─────────────────────────────────────────────────────────────
    //  并发
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("多个 bg task 并发:start 3 个,等全部完成,drain 返 3 条")
    void multiple_concurrent_bg_tasks() throws Exception {
        AtomicInteger completed = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(3);

        for (int i = 1; i <= 3; i++) {
            int idx = i;
            mgr.start("tu_" + idx, "cmd " + idx, () -> {
                completed.incrementAndGet();
                allDone.countDown();
                return new ToolResult(true, "out " + idx);
            });
        }

        assertTrue(allDone.await(2, TimeUnit.SECONDS),
                "3 个 bg task 应在 2s 内全部完成");
        assertEquals(3, completed.get());
        waitForResults(3);

        List<TextBlock> notifs = mgr.drainNotifications();
        assertEquals(3, notifs.size(), "应有 3 条通知");
        // 应按 bg_id 字典序(= 时间序)
        assertTrue(notifs.get(0).getText().contains("bg_0001"));
        assertTrue(notifs.get(1).getText().contains("bg_0002"));
        assertTrue(notifs.get(2).getText().contains("bg_0003"));
    }

    @Test
    @DisplayName("XML attribute 转义:command 含双引号 / 换行不破坏属性结构")
    void notification_escapes_attribute_chars() throws Exception {
        mgr.start("tu_1", "echo \"hello\nworld\"", () -> new ToolResult(true, "x"));
        waitForResults(1);

        String text = mgr.drainNotifications().get(0).getText();
        // 双引号转义为 &quot;,换行转空格 —— 让 attribute 仍是合法 XML
        assertTrue(text.contains("&quot;hello world&quot;") || text.contains("&quot;hello"),
                "双引号应被转义,实际:" + text);
        assertFalse(text.contains("command=\"echo \"hello"),
                "原始双引号不应出现在 attribute,实际:" + text);
    }

    // ─────────────────────────────────────────────────────────────
    //  helpers
    // ─────────────────────────────────────────────────────────────

    /** 轮询等待 results map 累积到指定数量(避免 work 返回到落 map 的微秒级 race)。 */
    private void waitForResults(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (mgr.pendingResultCount() >= expected) return;
            Thread.sleep(5);
        }
        fail("Expected " + expected + " pending results, got " + mgr.pendingResultCount());
    }

    @Test
    @DisplayName("BG 池满 → CallerRunsPolicy 同步降级,start 不抛(Stage 3 拆池)")
    void start_falls_back_to_sync_when_pool_full() throws Exception {
        // 用一个池满策略 = CallerRunsPolicy 的小池(模拟生产 bgExecutor)
        java.util.concurrent.ThreadPoolExecutor smallPool =
                new java.util.concurrent.ThreadPoolExecutor(
                        0, 1,
                        0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                        new java.util.concurrent.SynchronousQueue<>(),
                        r -> { Thread t = new Thread(r); t.setDaemon(true); return t; },
                        new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
                );
        BackgroundTaskManager smallMgr = new BackgroundTaskManager(smallPool);

        // 占满那唯一 1 槽 —— 派一个永久阻塞的任务
        java.util.concurrent.CountDownLatch holdSlot = new java.util.concurrent.CountDownLatch(1);
        smallMgr.start("tu_hold", "block", () -> {
            try { holdSlot.await(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ToolResult(true, "done");
        });
        Thread.sleep(50);

        // 第二个任务**不应抛**(CallerRunsPolicy)
        // 它会在 caller 线程同步跑(等于本次没派 bg)→ start 阻塞到 work 跑完才返回
        long t0 = System.nanoTime();
        java.util.concurrent.atomic.AtomicBoolean ranSync =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        String bgId = smallMgr.start("tu_2", "echo", () -> {
            ranSync.set(true);
            return new ToolResult(true, "x");
        });
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertNotNull(bgId, "CallerRunsPolicy 不抛异常,bgId 仍然分配");
        assertTrue(ranSync.get(), "work 应已在 caller 线程同步跑完");
        // 同步跑会让 start 阻塞 —— 应该 > 0,但又不应太久(我们的 work 是即时的)
        assertTrue(elapsedMs < 1000, "同步降级应快速完成,实际:" + elapsedMs + "ms");

        holdSlot.countDown();
        smallPool.shutdownNow();
    }
}
