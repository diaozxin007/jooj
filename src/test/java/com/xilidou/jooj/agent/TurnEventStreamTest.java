package com.xilidou.jooj.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * s22 D-11-b:{@link TurnEventStream} 单测,覆盖:
 * <ol>
 *   <li>push / since 基本 round-trip</li>
 *   <li>seq 单调递增</li>
 *   <li>since 过滤:只返 seq > threshold</li>
 *   <li>多 sid 隔离</li>
 *   <li>ring buffer 溢出丢最老</li>
 *   <li>clear 清空</li>
 *   <li>null / blank 防御</li>
 *   <li>并发 push safe</li>
 * </ol>
 */
class TurnEventStreamTest {

    @Test
    @DisplayName("push 后 since=0 拿到全部,seq 从 1 开始单调递增")
    void push_since_roundtrip() {
        var stream = new TurnEventStream();
        stream.push("sid", TurnEvent.toolStart("$ ls"));
        stream.push("sid", TurnEvent.toolStart("$ grep foo"));
        stream.push("sid", TurnEvent.toolStart("$ mvn test"));

        List<TurnEvent> all = stream.since("sid", 0);
        assertEquals(3, all.size());
        assertEquals(1L, all.get(0).seq());
        assertEquals(2L, all.get(1).seq());
        assertEquals(3L, all.get(2).seq());
        assertEquals("$ ls", all.get(0).summary());
        assertEquals("tool_start", all.get(0).type());
        assertEquals(3L, stream.latestSeq("sid"));
    }

    @Test
    @DisplayName("since 增量语义:since=2 只返回 seq=3+")
    void since_returns_only_greater() {
        var stream = new TurnEventStream();
        stream.push("sid", TurnEvent.toolStart("a"));
        stream.push("sid", TurnEvent.toolStart("b"));
        stream.push("sid", TurnEvent.toolStart("c"));

        List<TurnEvent> tail = stream.since("sid", 2);
        assertEquals(1, tail.size());
        assertEquals(3L, tail.get(0).seq());
        assertEquals("c", tail.get(0).summary());
    }

    @Test
    @DisplayName("since 大于 latestSeq → 空列表(前端 poll 到就无新事件)")
    void since_beyond_latest_returns_empty() {
        var stream = new TurnEventStream();
        stream.push("sid", TurnEvent.toolStart("only one"));
        assertEquals(1L, stream.latestSeq("sid"));
        assertTrue(stream.since("sid", 999).isEmpty());
    }

    @Test
    @DisplayName("多 sid 隔离:sid A 的事件不影响 sid B")
    void multi_sid_isolation() {
        var stream = new TurnEventStream();
        stream.push("A", TurnEvent.toolStart("a1"));
        stream.push("A", TurnEvent.toolStart("a2"));
        stream.push("B", TurnEvent.toolStart("b1"));

        assertEquals(2, stream.since("A", 0).size());
        assertEquals(1, stream.since("B", 0).size());
        // seq 独立编号
        assertEquals(1L, stream.since("B", 0).get(0).seq());
    }

    @Test
    @DisplayName("ring buffer 溢出:超过 MAX_PER_SESSION 后丢最老")
    void ring_buffer_evicts_oldest() {
        var stream = new TurnEventStream();
        int over = TurnEventStream.MAX_PER_SESSION + 10;
        for (int i = 0; i < over; i++) {
            stream.push("sid", TurnEvent.toolStart("evt-" + i));
        }
        // deque 最多 MAX 条
        assertEquals(TurnEventStream.MAX_PER_SESSION, stream.size("sid"));
        // seq 计数器仍到 over
        assertEquals(over, stream.latestSeq("sid"));

        List<TurnEvent> all = stream.since("sid", 0);
        assertEquals(TurnEventStream.MAX_PER_SESSION, all.size(),
                "since=0 只能拿到 buffer 里剩下的");
        // 最老的应该是 seq = over - MAX + 1
        long expectedFirst = over - TurnEventStream.MAX_PER_SESSION + 1;
        assertEquals(expectedFirst, all.get(0).seq(),
                "溢出后老的被丢,最早 seq 应变大");
        assertEquals("evt-" + (over - 1), all.get(all.size() - 1).summary());
    }

    @Test
    @DisplayName("clear 后 since 返空,latestSeq=0")
    void clear_empties_session() {
        var stream = new TurnEventStream();
        stream.push("sid", TurnEvent.toolStart("x"));
        assertEquals(1, stream.since("sid", 0).size());

        stream.clear("sid");
        assertTrue(stream.since("sid", 0).isEmpty());
        assertEquals(0L, stream.latestSeq("sid"),
                "clear 后 latestSeq 应重置(整个 SessionEvents 都清除)");
    }

    @Test
    @DisplayName("null / blank 防御:不抛异常,静默跳过")
    void null_or_blank_defensive() {
        var stream = new TurnEventStream();
        stream.push(null, TurnEvent.toolStart("x"));
        stream.push("", TurnEvent.toolStart("x"));
        stream.push("  ", TurnEvent.toolStart("x"));
        stream.push("sid", null);
        assertTrue(stream.since(null, 0).isEmpty());
        assertTrue(stream.since("", 0).isEmpty());
        assertEquals(0L, stream.latestSeq(null));
        stream.clear(null);
        stream.clear("");
    }

    @Test
    @DisplayName("TurnEvent 字段完整:at 非 null,type='tool_start',summary 保留")
    void turn_event_fields_preserved() {
        var stream = new TurnEventStream();
        Instant before = Instant.now();
        stream.push("sid", TurnEvent.toolStart("$ mvn"));
        Instant after = Instant.now();

        var e = stream.since("sid", 0).get(0);
        assertEquals("tool_start", e.type());
        assertEquals("$ mvn", e.summary());
        assertNotNull(e.at());
        assertFalse(e.at().isBefore(before));
        assertFalse(e.at().isAfter(after));
    }

    @Test
    @DisplayName("并发 push 安全:100 线程各 push 10 条,总共 1000 条,seq 无重复无丢失")
    void concurrent_push_safe() throws Exception {
        var stream = new TurnEventStream();
        int threads = 100;
        int perThread = 10;

        var futures = new CompletableFuture[threads];
        for (int i = 0; i < threads; i++) {
            final int tid = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                for (int j = 0; j < perThread; j++) {
                    stream.push("sid", TurnEvent.toolStart("t" + tid + "-e" + j));
                }
            });
        }
        CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);

        // 总条数正确(1000 > MAX_PER_SESSION 200,所以 deque 只有 200 条),seq 到 1000
        assertEquals(threads * perThread, stream.latestSeq("sid"));
        assertEquals(TurnEventStream.MAX_PER_SESSION, stream.size("sid"));

        // seq 单调 + 无重复
        List<TurnEvent> all = stream.since("sid", 0);
        long prev = 0;
        for (TurnEvent e : all) {
            assertTrue(e.seq() > prev, "seq 应严格递增,实际 " + prev + " → " + e.seq());
            prev = e.seq();
        }
    }
}
