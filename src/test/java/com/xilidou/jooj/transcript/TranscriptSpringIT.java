package com.xilidou.jooj.transcript;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring 装配 + 事件流转集成测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>{@link TranscriptConfiguration} 装成 bean(依赖 {@code joojObjectMapper} 存在)</li>
 *   <li>{@link TranscriptService} 的 4 个 {@code @EventListener} 被 Spring 注册</li>
 *   <li>通过 {@link ApplicationEventPublisher} 发事件,transcript 真实落盘</li>
 * </ul>
 *
 * <p>这是 §7 验收标准里 N5.1 幂等 + N1.1/N1.2/N1.3 落盘的**Spring 装配层**兜底 ——
 * 单测已经从方法层覆盖过语义,这里锁 Spring 边界。
 *
 * <p>用 {@code @TestInstance(PER_CLASS)} + {@code @BeforeAll}/{@code @AfterAll} 只启一次容器。
 * 每个 test 用独立 sessionId(sid-a / sid-b / sid-c)隔离,避免互扰。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TranscriptSpringIT {

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TranscriptService transcriptService;

    @Autowired
    TranscriptStore transcriptStore;

    Path transcriptsDir;

    @BeforeAll
    void snapshotDir() {
        transcriptsDir = transcriptStore.transcriptsDir();
        assertNotNull(transcriptsDir, "TranscriptStore 应装配 transcriptsDir");
    }

    @AfterAll
    void cleanup() throws IOException {
        // 清 sid-a/b/c 主文件和归档,不影响其他跑
        for (String sid : List.of("sid-a", "sid-b", "sid-c")) {
            Files.deleteIfExists(transcriptsDir.resolve(sid + ".jsonl"));
        }
        Path deleted = transcriptsDir.resolve(".deleted");
        if (Files.exists(deleted)) {
            try (var stream = Files.list(deleted)) {
                stream.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("sid-a-") || n.startsWith("sid-b-") || n.startsWith("sid-c-");
                }).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignore) {}
                });
            }
        }
    }

    @Test
    @DisplayName("publishEvent(UserMessageReceived) 通过 Spring 派发 → transcript 落盘")
    void publish_user_event_lands_on_disk() throws IOException {
        publisher.publishEvent(new UserMessageReceived(
                UUID.randomUUID(), "sid-a", "hello world",
                Instant.parse("2026-07-13T10:00:00Z"), "web"));

        List<TranscriptLine> lines = transcriptService.readAll("sid-a");
        assertEquals(1, lines.size(), "Spring 应把事件派发给 TranscriptService.onUserMessage");
        assertEquals("user", lines.get(0).role());
        assertEquals("hello world", lines.get(0).content());
        assertEquals("web", lines.get(0).source());
    }

    @Test
    @DisplayName("publishEvent(ScheduledPromptFired) → role=scheduled + source=cron:jobId")
    void publish_scheduled_event_lands_correctly() throws IOException {
        publisher.publishEvent(new ScheduledPromptFired(
                UUID.randomUUID(), "sid-b", "check deploy", "job-42",
                Instant.parse("2026-07-13T10:00:00Z")));

        List<TranscriptLine> lines = transcriptService.readAll("sid-b");
        assertEquals(1, lines.size());
        assertEquals("scheduled", lines.get(0).role());
        assertEquals("check deploy", lines.get(0).content());
        assertEquals("cron:job-42", lines.get(0).source());
    }

    @Test
    @DisplayName("N5.1 幂等 —— 同一事件通过 publisher 发两次,只落一行")
    void publish_same_event_twice_dedupes() throws IOException {
        UUID id = UUID.randomUUID();
        UserMessageReceived e = new UserMessageReceived(
                id, "sid-c", "hi", Instant.parse("2026-07-13T10:00:00Z"), "web");

        publisher.publishEvent(e);
        publisher.publishEvent(e);
        publisher.publishEvent(e);

        assertEquals(1, transcriptService.readAll("sid-c").size(),
                "3 次 publish 只落 1 行 —— Spring 层去重锁定 D11");
    }
}
