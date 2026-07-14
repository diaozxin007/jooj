package com.xilidou.jooj.transcript;

import com.xilidou.jooj.config.JsonMappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link TranscriptService} 的事件处理契约。
 *
 * <p>关注点(对应 s22 决策):
 * <ul>
 *   <li>D3 —— assistant 只放 final text,blank 内容跳过</li>
 *   <li>D6 —— SessionDeleted 触发 softDelete</li>
 *   <li>B1 —— UserMessageReceived source="cron:jobId" 落 role="scheduled"</li>
 *   <li>D11 —— 幂等:同 eventId 只落一次;失败回退允许重试</li>
 * </ul>
 *
 * <p>不测 Spring 上下文(EventListener 装配那部分留给 IT 测试),这里只测方法本身语义。
 */
class TranscriptServiceTest {

    @TempDir
    Path tmp;

    TranscriptStore store;
    TranscriptService service;

    @BeforeEach
    void setUp() {
        store = new TranscriptStore(tmp, JsonMappers.newMapper());
        service = new TranscriptService(store);
    }

    @Nested
    @DisplayName("正常事件处理")
    class Normal {

        @Test
        @DisplayName("UserMessageReceived 落 role=user + source")
        void user_event_appends_user_line() throws IOException {
            service.onUserMessage(new UserMessageReceived(
                    UUID.randomUUID(), "s1", "hi",
                    Instant.parse("2026-07-13T10:00:00Z"), "web"));

            List<TranscriptLine> lines = store.readAll("s1");
            assertEquals(1, lines.size());
            assertEquals("user", lines.get(0).role());
            assertEquals("hi", lines.get(0).content());
            assertEquals("web", lines.get(0).source());
        }

        @Test
        @DisplayName("UserMessageReceived source=cron:xxx → role=scheduled (B1 合并事件)")
        void cron_source_maps_to_scheduled_role() throws IOException {
            service.onUserMessage(new UserMessageReceived(
                    UUID.randomUUID(), "s1", "check deploy",
                    Instant.parse("2026-07-13T10:00:00Z"), "cron:job-42"));

            List<TranscriptLine> lines = store.readAll("s1");
            assertEquals(1, lines.size());
            assertEquals("scheduled", lines.get(0).role(),
                    "s22 B1:source 前缀 cron: 时 TranscriptService 落成 role=scheduled");
            assertEquals("check deploy", lines.get(0).content(),
                    "干净原文,不带 [Scheduled] 前缀");
            assertEquals("cron:job-42", lines.get(0).source());
        }

        @Test
        @DisplayName("AssistantResponseCompleted 落 role=assistant + source=null")
        void assistant_event_appends_assistant_line() throws IOException {
            service.onAssistantResponse(new AssistantResponseCompleted(
                    UUID.randomUUID(), "s1", "done",
                    Instant.parse("2026-07-13T10:00:00Z")));

            List<TranscriptLine> lines = store.readAll("s1");
            assertEquals(1, lines.size());
            assertEquals("assistant", lines.get(0).role());
            assertEquals("done", lines.get(0).content());
            assertNull(lines.get(0).source(), "assistant source 应为 null");
        }

        @Test
        @DisplayName("blank content 的 assistant 事件被跳过(D3 边界)")
        void blank_assistant_content_is_skipped() throws IOException {
            service.onAssistantResponse(new AssistantResponseCompleted(
                    UUID.randomUUID(), "s1", "  ",
                    Instant.parse("2026-07-13T10:00:00Z")));
            service.onAssistantResponse(new AssistantResponseCompleted(
                    UUID.randomUUID(), "s1", null,
                    Instant.parse("2026-07-13T10:00:00Z")));

            assertTrue(store.readAll("s1").isEmpty(), "blank/null 都跳过");
        }

        @Test
        @DisplayName("cron 场景下 scheduled + assistant 两行都出现(B1)")
        void cron_scenario_yields_scheduled_and_assistant() throws IOException {
            service.onUserMessage(new UserMessageReceived(
                    UUID.randomUUID(), "s1", "check deploy", Instant.now(), "cron:job-42"));
            service.onAssistantResponse(new AssistantResponseCompleted(
                    UUID.randomUUID(), "s1", "deploy healthy", Instant.now()));

            List<TranscriptLine> lines = store.readAll("s1");
            assertEquals(2, lines.size());
            assertEquals("scheduled", lines.get(0).role());
            assertEquals("assistant", lines.get(1).role());
        }
    }

    @Nested
    @DisplayName("D6 软归档")
    class SoftDeleteBehavior {

        @Test
        @DisplayName("SessionDeleted 触发 softDelete,原文件消失、.deleted 存在")
        void deletion_soft_archives() throws IOException {
            UUID e1 = UUID.randomUUID();
            service.onUserMessage(new UserMessageReceived(
                    e1, "s1", "hi", Instant.now(), "web"));

            Instant at = Instant.parse("2026-07-13T10:00:00Z");
            service.onSessionDeleted(new SessionDeleted(UUID.randomUUID(), "s1", at));

            assertTrue(store.readAll("s1").isEmpty(), "原文件不在了");
            assertTrue(tmp.resolve(".deleted")
                    .resolve("s1-" + at.toEpochMilli() + ".jsonl")
                    .toFile().exists());
        }
    }

    @Nested
    @DisplayName("D11 幂等")
    class Dedup {

        @Test
        @DisplayName("同 eventId 只落一次")
        void same_event_id_landed_once() throws IOException {
            UUID id = UUID.randomUUID();
            UserMessageReceived e = new UserMessageReceived(
                    id, "s1", "hi", Instant.now(), "web");

            service.onUserMessage(e);
            service.onUserMessage(e);   // 立刻重发同 event
            service.onUserMessage(e);

            assertEquals(1, store.readAll("s1").size(),
                    "3 次 publish 只落 1 行,LRU 拒重复");
        }

        @Test
        @DisplayName("不同 eventId 各落一次")
        void different_event_ids_all_landed() throws IOException {
            for (int i = 0; i < 3; i++) {
                service.onUserMessage(new UserMessageReceived(
                        UUID.randomUUID(), "s1", "msg-" + i, Instant.now(), "web"));
            }
            assertEquals(3, store.readAll("s1").size());
        }

        @Test
        @DisplayName("写盘失败时 LRU 回退,允许后续同 eventId 重试")
        void failure_releases_lru_for_retry() throws IOException {
            // 用一个抛异常的 store 模拟磁盘满
            TranscriptStore failingStore = new TranscriptStore(tmp, JsonMappers.newMapper()) {
                boolean firstCall = true;

                @Override
                public void append(String sessionId, TranscriptLine line) throws IOException {
                    if (firstCall) {
                        firstCall = false;
                        throw new IOException("disk full");
                    }
                    super.append(sessionId, line);
                }
            };
            TranscriptService svc = new TranscriptService(failingStore);
            UUID id = UUID.randomUUID();
            UserMessageReceived e = new UserMessageReceived(
                    id, "s1", "hi", Instant.now(), "web");

            svc.onUserMessage(e);                      // 第一次:抛 IOException,LRU 回退
            assertEquals(0, svc.seenEventsSize(), "失败后 LRU 空,允许重试");

            svc.onUserMessage(e);                      // 第二次:同 eventId 成功
            assertEquals(1, failingStore.readAll("s1").size());
            assertEquals(1, svc.seenEventsSize());
        }

        @Test
        @DisplayName("SessionDeleted 也走幂等(重复删除只归档一次)")
        void session_deleted_is_idempotent_via_dedup() throws IOException {
            UUID id = UUID.randomUUID();
            service.onUserMessage(new UserMessageReceived(
                    UUID.randomUUID(), "s1", "hi", Instant.now(), "web"));

            SessionDeleted del = new SessionDeleted(id, "s1",
                    Instant.parse("2026-07-13T10:00:00Z"));

            service.onSessionDeleted(del);
            service.onSessionDeleted(del);  // 同 eventId 重发不再触发第二次 softDelete

            // 只有一份归档(如果两次都触发,第二次 softDelete 会 no-op 也没问题,
            // 但 dedup gate 应该在 store 层之前就拦下来)
            long archivedCount = java.util.Arrays.stream(
                    tmp.resolve(".deleted").toFile().listFiles())
                    .filter(f -> f.getName().startsWith("s1-"))
                    .count();
            assertEquals(1, archivedCount);
        }
    }
}
