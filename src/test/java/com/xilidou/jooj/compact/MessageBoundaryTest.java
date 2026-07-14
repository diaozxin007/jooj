package com.xilidou.jooj.compact;

import com.xilidou.jooj.http.dto.ContentBlock;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link MessageBoundary} 的核心不变量:
 *
 * <p><b>核心契约</b>: adjustHeadEnd / adjustTailStart 之后,
 * <ul>
 *   <li>head 范围 msgs[0..headEnd) 里出现的每个 tool_use.id, 都有对应的 tool_result 在同范围内</li>
 *   <li>tail 范围 msgs[tailStart..) 里出现的每个 tool_result.tool_use_id, 都有对应的 tool_use 在同范围内</li>
 * </ul>
 *
 * <p><b>测试用例来源</b>: Demo 25 修复的 self-consistency walk bug ——
 * 中间隔着 thinking / text 消息的孤儿 tool_use ↔ tool_result 对,老实现只看相邻 1 格漏判。
 */
class MessageBoundaryTest {

    // ────────────────────────────────────────────────────────────
    //  fixture 构造
    // ────────────────────────────────────────────────────────────

    private static MessageParam userText(String s) {
        return MessageParam.user(s);
    }

    private static MessageParam assistantText(String s) {
        return new MessageParam("assistant", List.of((ContentBlock) new TextBlock(s)));
    }

    /** assistant 消息:纯 text + 一个 tool_use. */
    private static MessageParam assistantWithToolUse(String text, String useId, String toolName) {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock(text));
        blocks.add(new ToolUseBlock(useId, toolName, null));
        return new MessageParam("assistant", blocks);
    }

    /** user 消息:一个 tool_result. */
    private static MessageParam userWithToolResult(String useId, String output) {
        return new MessageParam("user", List.of((ContentBlock) ToolResultBlock.ofText(useId, output)));
    }

    // ────────────────────────────────────────────────────────────
    //  adjustHeadEnd
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("adjustHeadEnd(head 端孤儿保护)")
    class HeadEnd {

        @Test
        @DisplayName("headEnd=0 → 不变(head 是空)")
        void head_end_zero_returns_zero() {
            List<MessageParam> msgs = List.of(
                    userText("q"), assistantText("a"), userText("q2"));
            assertEquals(0, MessageBoundary.adjustHeadEnd(msgs, 0));
        }

        @Test
        @DisplayName("headEnd >= size → 不变(head 已覆盖全部)")
        void head_end_at_size_returns_size() {
            List<MessageParam> msgs = List.of(userText("q"), assistantText("a"));
            assertEquals(2, MessageBoundary.adjustHeadEnd(msgs, 2));
        }

        @Test
        @DisplayName("head 内无 tool_use → 不用扩")
        void no_tool_use_no_adjust() {
            List<MessageParam> msgs = List.of(
                    userText("q1"),
                    assistantText("a1"),
                    userText("q2"),
                    assistantText("a2"));
            assertEquals(2, MessageBoundary.adjustHeadEnd(msgs, 2));
        }

        @Test
        @DisplayName("head 内有孤儿 tool_use → 扩到覆盖 tool_result")
        void expand_to_cover_tool_result() {
            // [0] user "q"
            // [1] assistant text+tool_use(id=X)   ← head 只到这里,tool_use 孤儿
            // [2] user tool_result(X)             ← 必须一起进 head
            // [3] user "q2"
            List<MessageParam> msgs = List.of(
                    userText("q"),
                    assistantWithToolUse("using X", "id-X", "bash"),
                    userWithToolResult("id-X", "output"),
                    userText("q2"));
            int adjusted = MessageBoundary.adjustHeadEnd(msgs, 2);
            assertEquals(3, adjusted, "headEnd 应从 2 扩到 3(把 tool_result 包进来)");
        }

        @Test
        @DisplayName("head 内 tool_use + 同 message 内 tool_result → 不用扩")
        void tool_use_and_result_already_paired_in_head() {
            // 罕见情况:一条 message 同时含 tool_use 和 tool_result(不太符合协议但测代码健壮性)
            List<ContentBlock> blocks = new ArrayList<>();
            blocks.add(new ToolUseBlock("id-X", "bash", null));
            blocks.add(ToolResultBlock.ofText("id-X", "out"));
            MessageParam weird = new MessageParam("assistant", blocks);
            List<MessageParam> msgs = List.of(userText("q"), weird, userText("q2"));
            assertEquals(2, MessageBoundary.adjustHeadEnd(msgs, 2),
                    "同一 message 内 use+result 已配对,head 不需要扩");
        }

        @Test
        @DisplayName("跨多条消息的隔断 tool_use ↔ tool_result → walk 多轮补足")
        void multi_hop_expansion() {
            // [0] user "q"
            // [1] assistant tool_use(A)
            // [2] user tool_result(A)
            // [3] assistant tool_use(B)      ← 头切到这里,B 是孤儿
            // [4] user tool_result(B)
            // 从 headEnd=4 出发,head=[0..3] 有 A 且 A 配对 OK,还有 B 孤儿 → 扩到 5
            List<MessageParam> msgs = List.of(
                    userText("q"),
                    assistantWithToolUse("using A", "A", "bash"),
                    userWithToolResult("A", "outA"),
                    assistantWithToolUse("using B", "B", "bash"),
                    userWithToolResult("B", "outB"));
            int adjusted = MessageBoundary.adjustHeadEnd(msgs, 4);
            assertEquals(5, adjusted);
        }

        @Test
        @DisplayName("head 内孤儿 tool_use 后续没配对 → 扩到末尾")
        void unpaired_all_the_way_expands_to_size() {
            // 极端情况:head 内 tool_use,但整个 msgs 后面都没有对应的 tool_result
            // 期望 headEnd 扩到 msgs.size()(SnipCompactor 会兜底不做裁剪)
            List<MessageParam> msgs = List.of(
                    userText("q"),
                    assistantWithToolUse("using X", "id-X", "bash"),
                    userText("q2"),
                    assistantText("a"));
            int adjusted = MessageBoundary.adjustHeadEnd(msgs, 2);
            assertEquals(msgs.size(), adjusted,
                    "找不到配对时 headEnd 应扩到 msgs.size(), 让 SnipCompactor 放弃裁剪");
        }
    }

    // ────────────────────────────────────────────────────────────
    //  adjustTailStart
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("adjustTailStart(tail 端孤儿保护)")
    class TailStart {

        @Test
        @DisplayName("tailStart=0 → 不变(tail 覆盖全部)")
        void tail_start_zero_returns_zero() {
            List<MessageParam> msgs = List.of(
                    userText("q"), assistantText("a"));
            assertEquals(0, MessageBoundary.adjustTailStart(msgs, 0));
        }

        @Test
        @DisplayName("tailStart >= size → 不变(tail 是空)")
        void tail_start_at_size_returns_size() {
            List<MessageParam> msgs = List.of(userText("q"), assistantText("a"));
            assertEquals(2, MessageBoundary.adjustTailStart(msgs, 2));
        }

        @Test
        @DisplayName("tail 内无 tool_result → 不用缩")
        void no_tool_result_no_adjust() {
            List<MessageParam> msgs = List.of(
                    userText("q1"), assistantText("a1"),
                    userText("q2"), assistantText("a2"));
            assertEquals(2, MessageBoundary.adjustTailStart(msgs, 2));
        }

        @Test
        @DisplayName("tail 内孤儿 tool_result → 缩到覆盖 tool_use")
        void shrink_to_cover_tool_use() {
            // [0] user "q"
            // [1] assistant tool_use(X)      ← 待补进 tail
            // [2] user tool_result(X)        ← tail 起点(孤儿)
            // [3] user "q2"
            List<MessageParam> msgs = List.of(
                    userText("q"),
                    assistantWithToolUse("using X", "id-X", "bash"),
                    userWithToolResult("id-X", "output"),
                    userText("q2"));
            int adjusted = MessageBoundary.adjustTailStart(msgs, 2);
            assertEquals(1, adjusted, "tailStart 应从 2 缩到 1(把 tool_use 包进来)");
        }

        @Test
        @DisplayName("Demo 25 场景:thinking + text + tool_use + tool_result 隔多格 → walk 修复")
        void demo25_multi_hop_scenario() {
            // Demo 25 的关键 case:老实现只看相邻 1 格漏判。
            // 结构:
            //   [0] user "hi"
            //   [1] assistant text                        ← 没 tool_use
            //   [2] user "你用哪个模型"
            //   [3] assistant tool_use(date)
            //   [4] user tool_result(date)
            //   [5] assistant tool_use(schedule_cron)
            //   [6] user tool_result(schedule_cron)
            // 假设 SnipCompactor 想删中间 → tailStart=6
            // tail = [6] 是 tool_result(schedule_cron),配对的 tool_use 在 [5] → 缩到 5
            // 但 tail=[5..7) 只是 tool_use(schedule_cron),没有 unmatched tool_result 了,停。
            List<MessageParam> msgs = List.of(
                    userText("hi"),
                    assistantText("hello"),
                    userText("model?"),
                    assistantWithToolUse("checking date", "date-1", "date"),
                    userWithToolResult("date-1", "2026-07-03"),
                    assistantWithToolUse("scheduling", "cron-1", "schedule_cron"),
                    userWithToolResult("cron-1", "scheduled"));
            int adjusted = MessageBoundary.adjustTailStart(msgs, 6);
            assertEquals(5, adjusted, "tail 从 6 缩到 5 把 tool_use(cron-1) 包进来");
        }

        @Test
        @DisplayName("tail 内孤儿 tool_result 找不到配对 → 缩到 0")
        void unpaired_all_the_way_shrinks_to_zero() {
            // tail 里的 tool_result 在整个 msgs 找不到对应 tool_use(不应发生但测健壮性)
            // 期望缩到 0(SnipCompactor 会看到 tailStart=0 → 不做裁剪)
            List<MessageParam> msgs = List.of(
                    userText("q"),
                    assistantText("a"),
                    userWithToolResult("orphan-id", "output"));
            int adjusted = MessageBoundary.adjustTailStart(msgs, 2);
            assertEquals(0, adjusted, "找不到配对 tool_use 时 tailStart 应缩到 0");
        }
    }

    // ────────────────────────────────────────────────────────────
    //  hasToolUse / isToolResult 判定工具
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("消息类型判定")
    class Predicates {

        @Test
        @DisplayName("hasToolUse: assistant + 含 ToolUseBlock → true")
        void has_tool_use_true() {
            MessageParam m = assistantWithToolUse("t", "id", "bash");
            assertTrue(MessageBoundary.hasToolUse(m));
        }

        @Test
        @DisplayName("hasToolUse: assistant 但只含 text → false")
        void has_tool_use_false_text_only() {
            assertFalse(MessageBoundary.hasToolUse(assistantText("hi")));
        }

        @Test
        @DisplayName("hasToolUse: user + 含 ToolUseBlock(极端) → false(role 不对)")
        void has_tool_use_false_wrong_role() {
            MessageParam m = new MessageParam("user",
                    List.of((ContentBlock) new ToolUseBlock("id", "bash", null)));
            assertFalse(MessageBoundary.hasToolUse(m));
        }

        @Test
        @DisplayName("hasToolUse: content 是 String → false")
        void has_tool_use_false_string_content() {
            assertFalse(MessageBoundary.hasToolUse(userText("hi")));
        }

        @Test
        @DisplayName("isToolResult: user + 含 ToolResultBlock → true")
        void is_tool_result_true() {
            assertTrue(MessageBoundary.isToolResult(userWithToolResult("id", "o")));
        }

        @Test
        @DisplayName("isToolResult: user 但只含 text → false")
        void is_tool_result_false_text_only() {
            assertFalse(MessageBoundary.isToolResult(userText("hi")));
        }

        @Test
        @DisplayName("isToolResult: assistant + 含 ToolResultBlock(极端) → false(role 不对)")
        void is_tool_result_false_wrong_role() {
            MessageParam m = new MessageParam("assistant",
                    List.of((ContentBlock) ToolResultBlock.ofText("id", "o")));
            assertFalse(MessageBoundary.isToolResult(m));
        }
    }
}