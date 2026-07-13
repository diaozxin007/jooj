package com.xilidou.jooj.web;

import com.xilidou.jooj.transcript.TranscriptLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link TranscriptToChatItemMapper} 的映射契约。
 *
 * <p>关注点:
 * <ul>
 *   <li>3 种 role → 3 种 ChatItem.Type 映射</li>
 *   <li>id 稳定基于 index(tr-0 / tr-1 / ...)</li>
 *   <li>blank/null content 跳过</li>
 *   <li>未知 role 忽略(forward-compat)</li>
 *   <li>SYSTEM_NOTICE(CRON) 摘要含 cron:jobId</li>
 * </ul>
 */
class TranscriptToChatItemMapperTest {

    @Test
    @DisplayName("空 / null 输入返 empty list,不 NPE")
    void empty_input_returns_empty() {
        assertTrue(TranscriptToChatItemMapper.map(null).isEmpty());
        assertTrue(TranscriptToChatItemMapper.map(List.of()).isEmpty());
    }

    @Test
    @DisplayName("user role → USER_INPUT")
    void user_role_maps_to_user_input() {
        List<ChatItem> items = TranscriptToChatItemMapper.map(List.of(
                new TranscriptLine("user", "hello world",
                        Instant.parse("2026-07-13T10:00:00Z"), "session")));

        assertEquals(1, items.size());
        ChatItem item = items.get(0);
        assertEquals("tr-0", item.id());
        assertEquals(ChatItem.Type.USER_INPUT, item.type());
        assertEquals("user", item.role());
        assertEquals("hello world", item.text());
        assertNull(item.toolCall());
        assertNull(item.notice());
        assertEquals("2026-07-13T10:00:00Z", item.createdAt());
    }

    @Test
    @DisplayName("scheduled role → SYSTEM_NOTICE(CRON), summary 含 source")
    void scheduled_role_maps_to_system_notice_cron() {
        List<ChatItem> items = TranscriptToChatItemMapper.map(List.of(
                new TranscriptLine("scheduled", "check deploy",
                        Instant.parse("2026-07-13T14:00:00Z"), "cron:job-42")));

        assertEquals(1, items.size());
        ChatItem item = items.get(0);
        assertEquals("tr-0", item.id());
        assertEquals(ChatItem.Type.SYSTEM_NOTICE, item.type());
        assertEquals("system", item.role());
        assertNull(item.text(), "SYSTEM_NOTICE 的文字信息在 notice.fullText,不在 text");
        assertNotNull(item.notice());
        assertEquals(ChatItem.SystemNotice.Source.CRON, item.notice().source());
        assertTrue(item.notice().summary().contains("cron:job-42"),
                "summary 应含 source 信息,实际:" + item.notice().summary());
        assertEquals("check deploy", item.notice().fullText());
    }

    @Test
    @DisplayName("scheduled source 为 null 时 summary 仍合法(不 NPE)")
    void scheduled_null_source_still_valid() {
        List<ChatItem> items = TranscriptToChatItemMapper.map(List.of(
                new TranscriptLine("scheduled", "some prompt",
                        Instant.now(), null)));

        assertEquals(1, items.size());
        assertNotNull(items.get(0).notice());
        assertEquals("⏰ Scheduled", items.get(0).notice().summary(),
                "无 source 时 summary 只有 '⏰ Scheduled' 无后缀");
    }

    @Test
    @DisplayName("assistant role → ASSISTANT_TEXT")
    void assistant_role_maps_to_assistant_text() {
        List<ChatItem> items = TranscriptToChatItemMapper.map(List.of(
                new TranscriptLine("assistant", "here is my reply",
                        Instant.parse("2026-07-13T10:00:05Z"), null)));

        assertEquals(1, items.size());
        ChatItem item = items.get(0);
        assertEquals("tr-0", item.id());
        assertEquals(ChatItem.Type.ASSISTANT_TEXT, item.type());
        assertEquals("assistant", item.role());
        assertEquals("here is my reply", item.text());
    }

    @Test
    @DisplayName("blank / null content 的行被跳过")
    void blank_content_skipped() {
        List<ChatItem> items = TranscriptToChatItemMapper.map(List.of(
                new TranscriptLine("user", "keep me", Instant.now(), "session"),
                new TranscriptLine("assistant", "", Instant.now(), null),
                new TranscriptLine("user", "   ", Instant.now(), "session"),
                new TranscriptLine("assistant", "keep me too", Instant.now(), null)));

        assertEquals(2, items.size(), "blank 跳过,剩下 2 条");
        assertEquals("keep me", items.get(0).text());
        assertEquals("keep me too", items.get(1).text());
    }

    @Test
    @DisplayName("未知 role 被忽略(forward-compat)")
    void unknown_role_ignored() {
        List<ChatItem> items = TranscriptToChatItemMapper.map(List.of(
                new TranscriptLine("system", "future role", Instant.now(), null),
                new TranscriptLine("webhook", "future role 2", Instant.now(), null),
                new TranscriptLine("user", "known", Instant.now(), "session")));

        assertEquals(1, items.size(), "只留 user,unknown 忽略");
        assertEquals("known", items.get(0).text());
    }

    @Test
    @DisplayName("id 稳定基于 transcript 行 index(tr-{i})")
    void id_stable_by_transcript_index() {
        List<ChatItem> items = TranscriptToChatItemMapper.map(List.of(
                new TranscriptLine("user", "q1", Instant.now(), "session"),
                new TranscriptLine("assistant", "a1", Instant.now(), null),
                new TranscriptLine("scheduled", "cron", Instant.now(), "cron:j1"),
                new TranscriptLine("assistant", "a2", Instant.now(), null)));

        assertEquals("tr-0", items.get(0).id());
        assertEquals("tr-1", items.get(1).id());
        assertEquals("tr-2", items.get(2).id());
        assertEquals("tr-3", items.get(3).id());
    }

    @Test
    @DisplayName("端到端场景:cron + assistant + user + assistant 完整轮次")
    void end_to_end_scenario() {
        List<ChatItem> items = TranscriptToChatItemMapper.map(List.of(
                new TranscriptLine("scheduled", "check deploy",
                        Instant.parse("2026-07-13T14:00:00Z"), "cron:job-42"),
                new TranscriptLine("assistant", "deploy healthy",
                        Instant.parse("2026-07-13T14:00:12Z"), null),
                new TranscriptLine("user", "any error?",
                        Instant.parse("2026-07-13T14:05:00Z"), "web"),
                new TranscriptLine("assistant", "no errors",
                        Instant.parse("2026-07-13T14:05:03Z"), null)));

        assertEquals(4, items.size());
        assertEquals(ChatItem.Type.SYSTEM_NOTICE, items.get(0).type());
        assertEquals(ChatItem.Type.ASSISTANT_TEXT, items.get(1).type());
        assertEquals(ChatItem.Type.USER_INPUT, items.get(2).type());
        assertEquals(ChatItem.Type.ASSISTANT_TEXT, items.get(3).type());
    }

    // ── s22 D-8:interrupted role mapping ────────────────────────

    @Test
    @DisplayName("D-8 interrupted with partial content → SYSTEM_NOTICE 带 partial detail")
    void interrupted_with_partial_content() {
        List<ChatItem> items = TranscriptToChatItemMapper.map(List.of(
                new TranscriptLine("interrupted", "I was thinking about...",
                        Instant.parse("2026-07-13T14:00:00Z"), null)));

        assertEquals(1, items.size());
        ChatItem item = items.get(0);
        assertEquals(ChatItem.Type.SYSTEM_NOTICE, item.type());
        assertEquals("system", item.role());
        assertNotNull(item.notice());
        assertEquals("⛔ 已中断", item.notice().summary());
        assertEquals("I was thinking about...", item.notice().fullText());
    }

    @Test
    @DisplayName("D-8 interrupted with blank content 仍应保留(占位说明用户在出文本前打断)")
    void interrupted_with_blank_content_kept() {
        List<ChatItem> items = TranscriptToChatItemMapper.map(List.of(
                new TranscriptLine("interrupted", "",
                        Instant.parse("2026-07-13T14:00:00Z"), null)));

        assertEquals(1, items.size(),
                "interrupted 空 content 不该被过滤(它本身是有意义的系统事件)");
        assertEquals(ChatItem.Type.SYSTEM_NOTICE, items.get(0).type());
        assertTrue(items.get(0).notice().fullText().contains("打断"),
                "占位说明应含关键词'打断'");
    }
}
