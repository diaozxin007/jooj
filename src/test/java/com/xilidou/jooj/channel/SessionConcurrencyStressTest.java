package com.xilidou.jooj.channel;

import com.xilidou.jooj.JoojTestConfig;
import com.xilidou.jooj.http.MockAnthropicClient;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.http.ResponseFixtures;
import com.xilidou.jooj.session.SessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session 并发压测 —— 量化"不同 session 的 LLM 调用是否真能并行"。
 *
 * <h3>量化目标</h3>
 *
 * <p>每条 LLM 调用 mock 成 sleep 1.5s。两个不同 session 各发一条消息,
 * 用同一个 dispatchExecutor pool 跑:
 *
 * <ul>
 *   <li><b>串行 pool</b>(单线程):总耗时 ≈ 2 × 1.5 = 3s + 一次 memory consolidator</li>
 *   <li><b>并发 pool</b>(线程数 ≥ 2):总耗时 ≈ 1.5s + 一次 memory consolidator,接近 50% 提速</li>
 * </ul>
 *
 * <p><b>注意</b>:每次 dispatch 实际触发**两次** LLM call(主 turn + memory consolidator),
 * 但 memory consolidator 是 dispatch 返回**之后**才发,不影响并发观察。这里给两个 session
 * 各 mock 4 个 sleep response 兜底,实际只用前 2 个。
 *
 * <h3>这个测试的价值</h3>
 *
 * <p>建立 baseline + 验证改动后真的并发。**不假设任何具体 ms 数**,只断言"两个并发 ≪ 两个串行"。
 *
 * <p>注:dispatcher 本身不持线程池,并发性来自调用方 executor。本测试模拟 WeixinChannel 那边
 * 的派工 pool,直接验证 dispatcher 对并发是友好的。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(JoojTestConfig.class)
class SessionConcurrencyStressTest {

    /** 单条 LLM 调用注入的人为延迟 */
    private static final long LLM_DELAY_MS = 1_500;

    @Autowired InboundDispatcher dispatcher;
    @Autowired SessionService sessionService;
    @Autowired MockAnthropicClient mock;

    private FakeChannel fake;

    @BeforeEach
    void setUp() {
        // 清掉之前测试可能创建的 session
        for (String sid : List.of("chat_weixin_alice", "chat_weixin_bob")) {
            if (sessionService.exists(sid)) sessionService.clearHistory(sid);
        }
        fake = new FakeChannel();
        dispatcher.registerChannel(fake);

        // mock:每次 LLM 调用 sleep 1.5s,然后返回 endTurn("ack")。
        // 用 thread-safe responder 是因为我们等会要在多线程里调它。
        AtomicInteger callCount = new AtomicInteger();
        Function<CreateMessageRequest, CreateMessageResponse> responder = req -> {
            int n = callCount.incrementAndGet();
            try {
                Thread.sleep(LLM_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 偶数次是 memory consolidator(返非 JSON 走"无 memory"路径)
            return n % 2 == 0
                    ? ResponseFixtures.endTurn("[]")
                    : ResponseFixtures.endTurn("ack from call #" + n);
        };
        mock.reset(responder);
    }

    @AfterEach
    void tearDown() {
        dispatcher.unregisterChannel("weixin");
    }

    @Test
    @DisplayName("baseline: 单线程 pool 下两个 session dispatch → 总耗时 ≈ 2× 单次")
    void baseline_serial_executor_blocks_other_sessions() throws Exception {
        long elapsed = measureTwoConcurrentDispatches(1);
        System.out.printf("[STRESS] poolSize=1 (serial) elapsed=%dms%n", elapsed);
        assertTrue(elapsed >= 2 * LLM_DELAY_MS,
                "单线程下应至少 " + (2 * LLM_DELAY_MS) + "ms,实测 " + elapsed + "ms");
    }

    @Test
    @DisplayName("收益: 多线程 pool 下两个 session 真并行 → 总耗时 ≪ 串行 case")
    void multi_thread_pool_runs_sessions_in_parallel() throws Exception {
        long elapsed = measureTwoConcurrentDispatches(2);
        System.out.printf("[STRESS] poolSize=2 (parallel) elapsed=%dms%n", elapsed);
        long serialUpperBound = 4 * LLM_DELAY_MS;
        assertTrue(elapsed < serialUpperBound / 2 + 500,
                "并发应远低于串行(< " + (serialUpperBound / 2 + 500) + "ms),实测 " + elapsed + "ms");
        assertTrue(elapsed >= LLM_DELAY_MS,
                "至少要等一次 LLM 完成,实测 " + elapsed + "ms");
    }

    /**
     * 用指定线程数的 pool 起两个并发 dispatch,等都完成,返回总耗时。
     */
    private long measureTwoConcurrentDispatches(int poolSize) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "stress-dispatch");
            t.setDaemon(true);
            return t;
        });
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            List<Throwable> errors = new ArrayList<>();

            for (String peer : List.of("alice", "bob")) {
                pool.submit(() -> {
                    try {
                        start.await();
                        dispatcher.dispatch(new ChannelMessage(
                                "weixin", peer, null, "hello from " + peer, null));
                    } catch (Throwable t) {
                        synchronized (errors) { errors.add(t); }
                    } finally {
                        done.countDown();
                    }
                });
            }

            long t0 = System.nanoTime();
            start.countDown();   // 同时放两个开始
            assertTrue(done.await(30, TimeUnit.SECONDS), "两个 dispatch 应在 30s 内完成");
            long elapsed = (System.nanoTime() - t0) / 1_000_000;

            assertTrue(errors.isEmpty(),
                    "并发 dispatch 不该抛异常,实际: " + errors);
            return elapsed;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 出站记录器,跟 InboundDispatcherTest 同款。 */
    static class FakeChannel implements MessageChannel {
        record Out(String peer, String text) {}
        final List<Out> outbound = new ArrayList<>();

        @Override public String name() { return "weixin"; }
        @Override public void start(InboundDispatcher d) {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return true; }
        @Override public synchronized void sendOutbound(String peerId, String text) {
            outbound.add(new Out(peerId, text));
        }
    }
}
