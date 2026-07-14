package com.xilidou.jooj.transcript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JsonMappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link TranscriptStore} 的 IO 契约 —— framework-agnostic,
 * 直接 {@code new TranscriptStore(...)} 不走 Spring 容器。
 *
 * <p>覆盖:
 * <ul>
 *   <li>append round-trip(写一行 + 读回来)</li>
 *   <li>多条 append 顺序保持</li>
 *   <li>不存在的 session readAll 返空 list</li>
 *   <li>损坏行不阻断读整份文件</li>
 *   <li>softDelete 移动到 .deleted/,原文件消失</li>
 *   <li>softDelete idempotent(反复调不抛)</li>
 *   <li>路径注入防御</li>
 * </ul>
 */
class TranscriptStoreTest {

    @TempDir
    Path tmp;

    TranscriptStore store;
    ObjectMapper json;

    @BeforeEach
    void setUp() {
        json = JsonMappers.newMapper();
        store = new TranscriptStore(tmp, json);
    }

    @Nested
    @DisplayName("append + readAll")
    class Append {

        @Test
        @DisplayName("单行 append 后 readAll 拿到同一行")
        void single_line_round_trip() throws IOException {
            TranscriptLine line = new TranscriptLine(
                    "user", "hi", Instant.parse("2026-07-13T10:00:00Z"), "web");
            store.append("s1", line);

            List<TranscriptLine> read = store.readAll("s1");
            assertEquals(1, read.size());
            TranscriptLine r = read.get(0);
            assertEquals("user", r.role());
            assertEquals("hi", r.content());
            assertEquals("web", r.source());
            assertEquals(Instant.parse("2026-07-13T10:00:00Z"), r.timestamp());
        }

        @Test
        @DisplayName("多条 append 顺序保持(按写入顺序返回)")
        void multiple_lines_order_preserved() throws IOException {
            store.append("s1", new TranscriptLine("user", "q1", Instant.now(), "web"));
            store.append("s1", new TranscriptLine("assistant", "a1", Instant.now(), null));
            store.append("s1", new TranscriptLine("user", "q2", Instant.now(), "web"));

            List<TranscriptLine> read = store.readAll("s1");
            assertEquals(3, read.size());
            assertEquals("q1", read.get(0).content());
            assertEquals("a1", read.get(1).content());
            assertEquals("q2", read.get(2).content());
        }

        @Test
        @DisplayName("不存在的 session readAll 返空 list,不当错误")
        void missing_session_returns_empty() throws IOException {
            assertTrue(store.readAll("never-existed").isEmpty());
        }

        @Test
        @DisplayName("损坏行不阻断读整份文件")
        void malformed_line_skipped() throws IOException {
            // 手动写一个 malformed 后跟一个正常的
            Path path = tmp.resolve("s1.jsonl");
            Files.writeString(path, "{malformed json\n{\"role\":\"user\",\"content\":\"good\",\"timestamp\":\"2026-07-13T10:00:00Z\"}\n");

            List<TranscriptLine> read = store.readAll("s1");
            assertEquals(1, read.size(), "malformed 行被跳过,好的一行仍然读到");
            assertEquals("good", read.get(0).content());
        }

        @Test
        @DisplayName("source 为 null 不写入 JSON")
        void null_source_omitted() throws IOException {
            store.append("s1", new TranscriptLine(
                    "assistant", "reply", Instant.parse("2026-07-13T10:00:00Z"), null));
            String raw = Files.readString(tmp.resolve("s1.jsonl"));
            assertFalse(raw.contains("source"), "null source 字段应该被 NON_NULL 跳过, 实际:" + raw);
        }
    }

    @Nested
    @DisplayName("softDelete (D6)")
    class SoftDelete {

        @Test
        @DisplayName("softDelete 移到 .deleted/,原文件消失")
        void soft_delete_moves_to_archive() throws IOException {
            store.append("s1", new TranscriptLine("user", "hi", Instant.now(), "web"));
            assertTrue(Files.exists(tmp.resolve("s1.jsonl")));

            Instant at = Instant.parse("2026-07-13T10:00:00Z");
            store.softDelete("s1", at);

            assertFalse(Files.exists(tmp.resolve("s1.jsonl")), "原文件被移走");
            Path archived = tmp.resolve(".deleted").resolve("s1-" + at.toEpochMilli() + ".jsonl");
            assertTrue(Files.exists(archived), "归档文件存在: " + archived);
        }

        @Test
        @DisplayName("softDelete 对不存在的 session 是 no-op(idempotent)")
        void soft_delete_missing_is_noop() throws IOException {
            assertDoesNotThrow(() ->
                    store.softDelete("never-existed", Instant.now()));
        }

        @Test
        @DisplayName("softDelete 保留原文件内容")
        void soft_delete_preserves_content() throws IOException {
            store.append("s1", new TranscriptLine("user", "a", Instant.now(), "web"));
            store.append("s1", new TranscriptLine("user", "b", Instant.now(), "web"));

            Instant at = Instant.parse("2026-07-13T10:00:00Z");
            store.softDelete("s1", at);

            // 从归档路径读回来内容仍完整
            Path archived = tmp.resolve(".deleted").resolve("s1-" + at.toEpochMilli() + ".jsonl");
            List<String> lines = Files.readAllLines(archived);
            assertEquals(2, lines.size());
            assertTrue(lines.get(0).contains("\"content\":\"a\""));
            assertTrue(lines.get(1).contains("\"content\":\"b\""));
        }
    }

    @Nested
    @DisplayName("路径注入防御")
    class PathValidation {

        @Test
        @DisplayName("非法 sessionId 拒绝 append")
        void reject_bad_session_id_on_append() {
            assertThrows(IllegalArgumentException.class, () ->
                    store.append("../etc/passwd",
                            new TranscriptLine("user", "x", Instant.now(), "web")));
        }

        @Test
        @DisplayName("非法 sessionId 拒绝 readAll")
        void reject_bad_session_id_on_read() {
            assertThrows(IllegalArgumentException.class, () -> store.readAll("../evil"));
        }

        @Test
        @DisplayName("blank sessionId 拒绝")
        void reject_blank_session_id() {
            assertThrows(IllegalArgumentException.class, () ->
                    store.append("", new TranscriptLine("user", "x", Instant.now(), "web")));
            assertThrows(IllegalArgumentException.class, () ->
                    store.append("   ", new TranscriptLine("user", "x", Instant.now(), "web")));
        }
    }
}
