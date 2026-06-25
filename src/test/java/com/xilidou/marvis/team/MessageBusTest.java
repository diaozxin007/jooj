package com.xilidou.marvis.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link MessageBus} 的核心行为。
 *
 * <p>覆盖:
 * <ul>
 *   <li>send / readInbox 基础路径</li>
 *   <li>readInbox 消费式语义(读完邮箱被删)</li>
 *   <li>不存在邮箱 / 损坏行的容错</li>
 *   <li>并发 send + readInbox 不丢消息(per-agent ReentrantLock 验证)</li>
 *   <li>name 校验(防 path injection)</li>
 * </ul>
 */
class MessageBusTest {

    @TempDir
    Path tempDir;

    private MessageBus bus;

    @BeforeEach
    void setUp() {
        ObjectMapper json = JacksonConfig.newMapper();
        bus = new MessageBus(new TeamConfig(tempDir.resolve("mailboxes")), json);
    }

    @Test
    @DisplayName("send + readInbox roundtrip:5 字段都对得上")
    void send_read_roundtrip() {
        bus.send("alice", "lead", "Schema done", "result");
        List<Message> msgs = bus.readInbox("lead");

        assertEquals(1, msgs.size());
        Message m = msgs.get(0);
        assertEquals("alice", m.getFrom());
        assertEquals("lead", m.getTo());
        assertEquals("Schema done", m.getContent());
        assertEquals("result", m.getType());
        assertTrue(m.getTs() > 0, "ts 应该被分配");
    }

    @Test
    @DisplayName("readInbox 消费式:读完邮箱文件被删,再次读返空")
    void read_inbox_consumes() {
        bus.send("alice", "lead", "msg1", "message");
        bus.send("alice", "lead", "msg2", "message");

        assertEquals(2, bus.readInbox("lead").size());
        assertEquals(0, bus.readInbox("lead").size(),
                "再次读应返空(消费式)");
    }

    @Test
    @DisplayName("不存在邮箱 readInbox 返空 list,不抛")
    void read_missing_inbox_returns_empty() {
        List<Message> msgs = bus.readInbox("nobody");
        assertNotNull(msgs);
        assertTrue(msgs.isEmpty());
    }

    @Test
    @DisplayName("损坏 JSON 行跳过 + warn,不影响其他正常行")
    void corrupt_line_is_skipped() throws IOException {
        // 手工往邮箱写一行合法 + 一行损坏 + 一行合法
        Path mailbox = bus.mailboxDir().resolve("lead.jsonl");
        Files.createDirectories(mailbox.getParent());
        ObjectMapper json = JacksonConfig.newMapper();
        String good1 = json.writeValueAsString(new Message("a", "lead", "ok1", "message", 1));
        String good2 = json.writeValueAsString(new Message("b", "lead", "ok2", "message", 2));
        Files.writeString(mailbox,
                good1 + "\n" + "{ broken json }" + "\n" + good2 + "\n",
                StandardCharsets.UTF_8);

        List<Message> msgs = bus.readInbox("lead");
        assertEquals(2, msgs.size(), "损坏行应被跳过,两条正常消息保留");
        assertEquals("ok1", msgs.get(0).getContent());
        assertEquals("ok2", msgs.get(1).getContent());
    }

    @Test
    @DisplayName("send 默认 type=message 当传 null")
    void send_default_type_is_message() {
        bus.send("alice", "lead", "x", null);
        Message m = bus.readInbox("lead").get(0);
        assertEquals("message", m.getType());
    }

    @Test
    @DisplayName("name 校验:含 / \\ .. 抛 IllegalArgumentException")
    void name_validation_blocks_path_injection() {
        assertThrows(IllegalArgumentException.class,
                () -> bus.send("../../etc/passwd", "lead", "x", "message"));
        assertThrows(IllegalArgumentException.class,
                () -> bus.send("alice", "../../../etc", "x", "message"));
        assertThrows(IllegalArgumentException.class,
                () -> bus.readInbox("a/b"));
        assertThrows(IllegalArgumentException.class,
                () -> bus.readInbox(""));
    }

    @Test
    @DisplayName("并发 96 send + 单线程 read 不丢消息(per-agent lock 验证)")
    void concurrent_send_then_read_loses_no_messages() throws Exception {
        // 8 worker × 12 each = 96(刻意挑能整除的数,避免 / 8 余数干扰断言)
        int workers = 8;
        int perWorker = 12;
        int expectedTotal = workers * perWorker;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger();

        for (int i = 0; i < workers; i++) {
            int worker = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int j = 0; j < perWorker; j++) {
                        bus.send("worker" + worker, "lead", "m" + j, "message");
                        sent.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {}
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        List<Message> msgs = bus.readInbox("lead");
        assertEquals(expectedTotal, sent.get(), "所有 send 都应成功");
        assertEquals(expectedTotal, msgs.size(),
                "lock 应保证 send 不冲突,read 一次性 drain 到所有消息");
    }

    @Test
    @DisplayName("peekSize 不消费 —— 调用后 readInbox 仍能读到")
    void peek_size_does_not_consume() {
        bus.send("a", "lead", "x", "message");
        bus.send("b", "lead", "y", "message");

        assertEquals(2, bus.peekSize("lead"));
        assertEquals(2, bus.peekSize("lead"), "peek 不消费");
        assertEquals(2, bus.readInbox("lead").size(), "read 仍能拿到");
        assertEquals(0, bus.peekSize("lead"), "read 后 peek 返 0");
    }

    @Test
    @DisplayName("peekSize 不存在邮箱返 0")
    void peek_missing_returns_zero() {
        assertEquals(0, bus.peekSize("nobody"));
    }
}
