package com.xilidou.jooj.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ThinkingBlock;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatHistoryMapper 每一类"raw history 源"的映射都单独测。
 * 目的是给"什么该展示给用户 / 什么该藏"上锁 —— 未来 raw history 里加新种类的注入源时,
 * 测试会明确指出:你没写映射规则,mapper 会走 fallback 路径,可能被当成 USER_INPUT 混入对话。
 */
class ChatHistoryMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── 用户输入 ────────────────────────────────────────

    @Nested
    class UserInput {

        @Test
        void plain_user_string_becomes_USER_INPUT() {
            List<ChatItem> items = ChatHistoryMapper.map(List.of(
                    MessageParam.user("你好")));

            assertEquals(1, items.size());
            assertEquals(ChatItem.Type.USER_INPUT, items.get(0).type());
            assertEquals("你好", items.get(0).text());
            assertEquals("user", items.get(0).role());
        }

        @Test
        void relevant_memories_prefix_is_stripped() {
            String raw = "<relevant_memories>\n<memory>foo</memory>\n</relevant_memories>\n\nActual user question";
            List<ChatItem> items = ChatHistoryMapper.map(List.of(MessageParam.user(raw)));

            assertEquals(1, items.size());
            assertEquals("Actual user question", items.get(0).text());
        }

        @Test
        void reminder_suffix_is_stripped() {
            String raw = "please run tests\n\n<reminder>You haven't updated todos for 10 rounds</reminder>";
            List<ChatItem> items = ChatHistoryMapper.map(List.of(MessageParam.user(raw)));

            assertEquals(1, items.size());
            assertEquals("please run tests", items.get(0).text());
        }

        @Test
        void blank_user_input_is_skipped() {
            List<ChatItem> items = ChatHistoryMapper.map(List.of(
                    MessageParam.user(""),
                    MessageParam.user("   ")));

            assertTrue(items.isEmpty());
        }
    }

    // ── Assistant 文字 / thinking ────────────────────────

    @Nested
    class AssistantBlocks {

        @Test
        void assistant_text_block_becomes_ASSISTANT_TEXT() {
            List<ChatItem> items = ChatHistoryMapper.map(List.of(
                    MessageParam.assistant(List.of(new TextBlock("Hi there")))));

            assertEquals(1, items.size());
            assertEquals(ChatItem.Type.ASSISTANT_TEXT, items.get(0).type());
            assertEquals("Hi there", items.get(0).text());
        }

        @Test
        void thinking_block_becomes_THINKING_item_separate_from_text() {
            List<ChatItem> items = ChatHistoryMapper.map(List.of(
                    MessageParam.assistant(List.of(
                            new ThinkingBlock("let me think", "sig-1"),
                            new TextBlock("Answer: 42")))));

            assertEquals(2, items.size());
            assertEquals(ChatItem.Type.THINKING, items.get(0).type());
            assertEquals("let me think", items.get(0).text());
            assertEquals(ChatItem.Type.ASSISTANT_TEXT, items.get(1).type());
        }

        @Test
        void blank_text_block_is_skipped() {
            List<ChatItem> items = ChatHistoryMapper.map(List.of(
                    MessageParam.assistant(List.of(new TextBlock("")))));
            assertTrue(items.isEmpty());
        }
    }

    // ── Tool call + result 配对 ─────────────────────────

    @Nested
    class ToolCallPairing {

        @Test
        void tool_use_becomes_TOOL_CALL_and_result_backfills_preview() throws Exception {
            List<MessageParam> raw = List.of(
                    MessageParam.user("run ls"),
                    MessageParam.assistant(List.of(mkToolUse("tu_1", "bash", "{\"command\":\"ls\"}"))),
                    userToolResult("tu_1", "file1.txt\nfile2.txt"));

            List<ChatItem> items = ChatHistoryMapper.map(raw);

            // USER_INPUT + TOOL_CALL(result 回填,不新增 item)
            assertEquals(2, items.size());
            ChatItem toolItem = items.get(1);
            assertEquals(ChatItem.Type.TOOL_CALL, toolItem.type());
            ChatItem.ToolCall tc = toolItem.toolCall();
            assertEquals("bash", tc.name());
            assertEquals("tu_1", tc.toolUseId());
            assertTrue(tc.inputSummary().contains("ls"));
            assertEquals("file1.txt\nfile2.txt", tc.resultPreview());
            assertFalse(tc.resultTruncated());
            assertFalse(tc.isBackground());
            assertEquals("ok", tc.status());
        }

        @Test
        void error_result_marks_status_error() throws Exception {
            List<MessageParam> raw = List.of(
                    MessageParam.assistant(List.of(mkToolUse("tu_2", "read_file", "{\"path\":\"/nope\"}"))),
                    userToolResult("tu_2", "Error: file not found"));

            List<ChatItem> items = ChatHistoryMapper.map(raw);

            assertEquals(1, items.size());
            assertEquals("error", items.get(0).toolCall().status());
        }

        @Test
        void long_result_is_truncated_with_flag() throws Exception {
            String big = "x".repeat(2000);
            List<MessageParam> raw = List.of(
                    MessageParam.assistant(List.of(mkToolUse("tu_3", "bash", "{\"command\":\"seq\"}"))),
                    userToolResult("tu_3", big));

            List<ChatItem> items = ChatHistoryMapper.map(raw);
            ChatItem.ToolCall tc = items.get(0).toolCall();
            assertTrue(tc.resultTruncated());
            assertEquals(300, tc.resultPreview().length());
            assertEquals(2000, tc.resultFull().length());
        }

        @Test
        void orphan_tool_result_is_silently_skipped() {
            // 只有 tool_result 无对应 tool_use —— HistoryScrubber 通常清掉,mapper 至少不该崩
            List<MessageParam> raw = List.of(userToolResult("tu_unknown", "some output"));
            List<ChatItem> items = ChatHistoryMapper.map(raw);
            assertTrue(items.isEmpty());
        }

        @Test
        void background_bash_starts_as_pending_and_notification_updates_to_ok() throws Exception {
            String placeholder = "[Background task bg_0001 started] Result will be available when complete.";
            String notification = "<task_notification id=\"bg_0001\" command=\"npm install\">"
                    + "exit=0\nadded 42 packages"
                    + "</task_notification>";

            List<MessageParam> raw = List.of(
                    MessageParam.assistant(List.of(mkToolUse("tu_bg", "bash",
                            "{\"command\":\"npm install\",\"run_in_background\":true}"))),
                    userToolResult("tu_bg", placeholder),
                    // 下一轮 tool_results 消息里夹带 task_notification TextBlock
                    userToolResultWithNotification("tu_other", "other output",
                            notification));

            List<ChatItem> items = ChatHistoryMapper.map(raw);
            // 应该只有 1 张 tool_call 卡(bg),tu_other 找不到对应 tool_use,忽略
            assertEquals(1, items.size());
            ChatItem.ToolCall tc = items.get(0).toolCall();
            assertTrue(tc.isBackground());
            // 通知回填后 status 从 background_pending → ok
            assertEquals("ok", tc.status());
            assertTrue(tc.resultFull().contains("added 42 packages"));
            // 精确回填的前提:mapper 从 placeholder 里抠出 bg_0001 存到 ToolCall.backgroundId
            assertEquals("bg_0001", tc.backgroundId());
        }

        /**
         * 之前用 resultFull.contains(bgId) 做匹配,两个 bg 任务如果一个的输出恰好含另一个的
         * bgId 字符串,通知就会串味回到错误的卡上。改成 backgroundId 字段精确匹配后不会。
         */
        @Test
        void two_concurrent_bgs_notifications_route_to_correct_cards() throws Exception {
            List<MessageParam> raw = List.of(
                    // 起两个 bg
                    MessageParam.assistant(List.of(
                            mkToolUse("tu_a", "bash", "{\"command\":\"cmd A\",\"run_in_background\":true}"),
                            mkToolUse("tu_b", "bash", "{\"command\":\"cmd B\",\"run_in_background\":true}"))),
                    // 两条 placeholder 分别在各自 tool_use_id 下
                    userMultiToolResults(List.of(
                            new ToolResultLite("tu_a", "[Background task bg_0001 started]"),
                            new ToolResultLite("tu_b", "[Background task bg_0002 started]"))),
                    // 下一轮 dispatch 收到 bg_0002 的通知(注意:body 里故意含 "bg_0001" 字符串
                    // 来陷阱旧的 contains-based 匹配)
                    userToolResultWithNotification("tu_other", "unrelated",
                            "<task_notification id=\"bg_0002\" command=\"cmd B\">"
                                    + "done — took less time than bg_0001 would have"
                                    + "</task_notification>"));

            List<ChatItem> items = ChatHistoryMapper.map(raw);

            assertEquals(2, items.size());
            ChatItem.ToolCall a = items.get(0).toolCall();
            ChatItem.ToolCall b = items.get(1).toolCall();
            assertEquals("bg_0001", a.backgroundId());
            assertEquals("bg_0002", b.backgroundId());
            // bg_0002 收到通知 → status=ok;bg_0001 仍是 pending
            assertEquals("background_pending", a.status());
            assertEquals("ok", b.status());
            assertTrue(b.resultFull().contains("done"));
        }
    }

    // ── 系统注入(cron / inbox / L1 / L4 / error)─────────

    @Nested
    class SystemNotices {

        @Test
        void scheduled_prefix_becomes_CRON_notice() {
            List<ChatItem> items = ChatHistoryMapper.map(List.of(
                    MessageParam.user("[Scheduled] Remind the user: ⏰ Meeting reminder!")));

            assertEquals(1, items.size());
            ChatItem it = items.get(0);
            assertEquals(ChatItem.Type.SYSTEM_NOTICE, it.type());
            assertEquals(ChatItem.SystemNotice.Source.CRON, it.notice().source());
            assertTrue(it.notice().summary().startsWith("⏰"));
        }

        @Test
        void inbox_prefix_becomes_INBOX_notice_with_count() {
            List<ChatItem> items = ChatHistoryMapper.map(List.of(
                    MessageParam.user("[Inbox] 3 message(s) from teammates:\n  From alice: ok\n")));

            ChatItem.SystemNotice n = items.get(0).notice();
            assertEquals(ChatItem.SystemNotice.Source.INBOX, n.source());
            assertTrue(n.summary().contains("3"));
        }

        @Test
        void snipped_prefix_becomes_ARCHIVE_L1() {
            List<ChatItem> items = ChatHistoryMapper.map(List.of(
                    MessageParam.user("[snipped 7 messages, archived to /tmp/snip.jsonl]")));

            ChatItem.SystemNotice n = items.get(0).notice();
            assertEquals(ChatItem.SystemNotice.Source.ARCHIVE_L1, n.source());
            assertTrue(n.summary().contains("7"));
        }

        @Test
        void summary_prefix_becomes_ARCHIVE_L4() {
            List<ChatItem> items = ChatHistoryMapper.map(List.of(
                    MessageParam.user("[Conversation summary] (30 messages archived to /tmp/sum.jsonl): the user asked about X")));

            ChatItem.SystemNotice n = items.get(0).notice();
            assertEquals(ChatItem.SystemNotice.Source.ARCHIVE_L4, n.source());
        }

        @Test
        void recovery_fatal_error_becomes_ERROR_notice() {
            List<ChatItem> items = ChatHistoryMapper.map(List.of(
                    MessageParam.assistant(List.of(new TextBlock("[Error] Context length exceeded")))));

            assertEquals(1, items.size());
            ChatItem it = items.get(0);
            assertEquals(ChatItem.Type.SYSTEM_NOTICE, it.type());
            assertEquals(ChatItem.SystemNotice.Source.ERROR, it.notice().source());
            assertTrue(it.notice().summary().contains("Context length"));
        }
    }

    // ── 综合场景 ─────────────────────────────────────────

    @Nested
    class Integration {

        @Test
        void reported_bug_run_npm_install_and_read_readme() throws Exception {
            // 复现 bug: raw history 里 assistant 有 text,但接下来又追加了 [Inbox] user 注入
            // —— 展示层应该把 [Inbox] 与 assistant text 分开成两张卡,不混序
            List<MessageParam> raw = new ArrayList<>();
            raw.add(MessageParam.user("Run npm install in the background and continue reading README.md"));
            raw.add(MessageParam.assistant(List.of(
                    new ThinkingBlock("Both actions already done in previous turn.", "sig-x"),
                    new TextBlock("这两件事我们上一轮刚做过:\n- npm install 已在后台运行\n- README.md 上一轮已总结"))));
            raw.add(MessageParam.user("[Inbox] 2 message(s) from teammates:\n  From alice: review done"));

            List<ChatItem> items = ChatHistoryMapper.map(raw);

            assertEquals(4, items.size());
            assertEquals(ChatItem.Type.USER_INPUT, items.get(0).type());
            assertEquals(ChatItem.Type.THINKING, items.get(1).type());
            assertEquals(ChatItem.Type.ASSISTANT_TEXT, items.get(2).type());
            assertEquals(ChatItem.Type.SYSTEM_NOTICE, items.get(3).type());
            assertEquals(ChatItem.SystemNotice.Source.INBOX, items.get(3).notice().source());
            // assistant text 是完整的,没被 [Inbox] 污染
            assertTrue(items.get(2).text().contains("这两件事我们上一轮刚做过"));
            assertFalse(items.get(2).text().contains("[Inbox]"));
        }

        @Test
        void ids_are_stable_and_ordered_by_source_position() {
            List<MessageParam> raw = List.of(
                    MessageParam.user("q1"),
                    MessageParam.assistant(List.of(new TextBlock("a1"))),
                    MessageParam.user("q2"));

            List<ChatItem> items = ChatHistoryMapper.map(raw);
            assertEquals("msg-0", items.get(0).id());
            assertEquals("msg-1-b-0", items.get(1).id());
            assertEquals("msg-2", items.get(2).id());
        }

        @Test
        void empty_and_null_history_return_empty_list() {
            assertTrue(ChatHistoryMapper.map(null).isEmpty());
            assertTrue(ChatHistoryMapper.map(List.of()).isEmpty());
        }
    }

    // ── helpers ─────────────────────────────────────────

    private static ToolUseBlock mkToolUse(String id, String name, String inputJson) throws Exception {
        ToolUseBlock b = new ToolUseBlock();
        b.setId(id);
        b.setName(name);
        b.setInput(JSON.readTree(inputJson));
        return b;
    }

    private static MessageParam userToolResult(String toolUseId, String content) {
        ToolResultBlock trb = new ToolResultBlock();
        trb.setToolUseId(toolUseId);
        trb.setContent(content);
        return new MessageParam("user", new ArrayList<>(List.of(trb)));
    }

    private static MessageParam userToolResultWithNotification(String toolUseId, String content,
                                                                String notificationText) {
        ToolResultBlock trb = new ToolResultBlock();
        trb.setToolUseId(toolUseId);
        trb.setContent(content);
        return new MessageParam("user",
                new ArrayList<>(List.of(trb, new TextBlock(notificationText))));
    }

    /** 并列多条 tool_result 挂在同一 user 消息里(两个 tool_use 同轮回来的场景)。 */
    private static MessageParam userMultiToolResults(List<ToolResultLite> lites) {
        List<Object> blocks = new ArrayList<>();
        for (ToolResultLite l : lites) {
            ToolResultBlock trb = new ToolResultBlock();
            trb.setToolUseId(l.toolUseId);
            trb.setContent(l.content);
            blocks.add(trb);
        }
        return new MessageParam("user", blocks);
    }

    private record ToolResultLite(String toolUseId, String content) {}
}
