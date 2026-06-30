package com.xilidou.jooj.session;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ThinkingBlock;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link HistoryScrubber} 的核心行为(s21 Demo 25 副作用):
 *
 * <ul>
 *   <li>纯文本 history 原样返回</li>
 *   <li>孤儿 tool_result 块被过滤,保留正常配对</li>
 *   <li>整条 message 全是孤儿 → 整条 drop</li>
 *   <li>孤儿 tool_use(头部孤儿)同样被过滤</li>
 *   <li>TextBlock / ThinkingBlock / 其他 block 不被动</li>
 *   <li>无变化时返回原引用(避免无谓拷贝)</li>
 * </ul>
 */
class HistoryScrubberTest {

    private static MessageParam userText(String s) {
        return MessageParam.user(s);
    }

    private static MessageParam assistantTextBlocks(String text) {
        return new MessageParam("assistant", List.of(new TextBlock(text)));
    }

    private static MessageParam assistantToolUse(String id) {
        return new MessageParam("assistant",
                List.of(new ToolUseBlock(id, "test", JsonNodeFactory.instance.objectNode())));
    }

    private static MessageParam userToolResult(String id, String content) {
        return new MessageParam("user", List.of(ToolResultBlock.ofText(id, content)));
    }

    @Test
    @DisplayName("空 / null history 安全")
    void empty_history_safe() {
        assertNull(HistoryScrubber.scrub(null));
        assertTrue(HistoryScrubber.scrub(List.of()).isEmpty());
    }

    @Test
    @DisplayName("纯文本 history 不动 + 返回原引用")
    void plain_text_history_returns_same_reference() {
        List<MessageParam> hist = new ArrayList<>();
        hist.add(userText("你好"));
        hist.add(assistantTextBlocks("hi"));
        hist.add(userText("再见"));
        List<MessageParam> out = HistoryScrubber.scrub(hist);
        assertSame(hist, out, "无 tool 块时应返回原引用避免无谓拷贝");
    }

    @Test
    @DisplayName("正常配对 history 不动")
    void well_formed_pairs_unchanged() {
        List<MessageParam> hist = new ArrayList<>();
        hist.add(userText("query"));
        hist.add(assistantToolUse("u1"));
        hist.add(userToolResult("u1", "result"));
        hist.add(userText("ok"));
        List<MessageParam> out = HistoryScrubber.scrub(hist);
        assertEquals(4, out.size());
    }

    @Test
    @DisplayName("孤儿 tool_result(无对应 tool_use)整块被过滤,message 整条丢")
    void orphan_tool_result_dropped() {
        List<MessageParam> hist = new ArrayList<>();
        hist.add(userText("hi"));
        hist.add(userToolResult("missing_use_id", "ghost"));   // 孤儿
        hist.add(userText("end"));
        List<MessageParam> out = HistoryScrubber.scrub(hist);
        assertEquals(2, out.size());
        assertEquals("hi", out.get(0).getContent());
        assertEquals("end", out.get(1).getContent());
    }

    @Test
    @DisplayName("孤儿 tool_use(无对应 tool_result)同样被过滤")
    void orphan_tool_use_dropped() {
        List<MessageParam> hist = new ArrayList<>();
        hist.add(userText("hi"));
        hist.add(assistantToolUse("orphan_id"));   // 没人 result 它
        hist.add(userText("end"));
        List<MessageParam> out = HistoryScrubber.scrub(hist);
        assertEquals(2, out.size());
        // assistant message 整条丢(只有 1 个 tool_use 块,被丢后 empty)
    }

    @Test
    @DisplayName("混合场景:1 对正常 + 1 孤儿 result + 1 孤儿 use")
    void mixed_orphans_and_pairs() {
        List<MessageParam> hist = new ArrayList<>();
        hist.add(userText("q"));                                      // 0
        hist.add(assistantToolUse("good"));                           // 1
        hist.add(userToolResult("good", "rg"));                       // 2
        hist.add(assistantToolUse("orphan_use"));                     // 3 孤儿(没 result)
        hist.add(userToolResult("orphan_result", "rr"));              // 4 孤儿(没 use)
        hist.add(userText("end"));                                    // 5

        List<MessageParam> out = HistoryScrubber.scrub(hist);
        // 保留 [0, 1, 2, 5];3 / 4 都丢
        assertEquals(4, out.size());
        assertEquals("q", out.get(0).getContent());
        assertEquals("end", out.get(3).getContent());
    }

    @Test
    @DisplayName("一条 message 多块:好 block 留,坏 block 过滤")
    void per_block_filter_within_one_message() {
        List<MessageParam> hist = new ArrayList<>();
        hist.add(userText("q"));                                              // 0
        hist.add(assistantToolUse("good"));                                   // 1
        // 一条 user 同时含好 tool_result + 孤儿 tool_result + 普通 TextBlock
        hist.add(new MessageParam("user", List.of(
                ToolResultBlock.ofText("good", "good-result"),
                ToolResultBlock.ofText("missing", "ghost-result"),
                new TextBlock("note")
        )));                                                                  // 2
        List<MessageParam> out = HistoryScrubber.scrub(hist);
        assertEquals(3, out.size(), "整条 message 不应该被丢(还有合法块)");
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) out.get(2).getContent();
        assertEquals(2, blocks.size(), "孤儿 tool_result 块应被过滤,保留 good + TextBlock");
        assertTrue(blocks.get(0) instanceof ToolResultBlock);
        assertEquals("good", ((ToolResultBlock) blocks.get(0)).getToolUseId());
        assertTrue(blocks.get(1) instanceof TextBlock);
    }

    @Test
    @DisplayName("ThinkingBlock 不被动 + 同 message 跟 tool_use 共存")
    void thinking_block_untouched() {
        List<MessageParam> hist = new ArrayList<>();
        hist.add(userText("q"));
        hist.add(new MessageParam("assistant", List.of(
                new ThinkingBlock("internal", "sig"),
                new TextBlock("answer"),
                new ToolUseBlock("u1", "test", JsonNodeFactory.instance.objectNode())
        )));
        hist.add(userToolResult("u1", "ok"));
        List<MessageParam> out = HistoryScrubber.scrub(hist);
        assertEquals(3, out.size());
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) out.get(1).getContent();
        assertEquals(3, blocks.size(), "Thinking + Text + ToolUse 全部保留");
        assertTrue(blocks.get(0) instanceof ThinkingBlock);
    }

    @Test
    @DisplayName("Demo 25 实战 case 复现:[snipped N] 占位符 + 孤儿 tool_result")
    void demo25_real_world_case() {
        List<MessageParam> hist = new ArrayList<>();
        hist.add(userText("你好"));
        hist.add(assistantTextBlocks("hi back"));
        hist.add(userText("[snipped 3 messages]"));               // 占位符
        hist.add(userToolResult("toolu_bdrk_orphan", "ghost"));   // 真孤儿
        hist.add(assistantToolUse("toolu_bdrk_X"));
        hist.add(userToolResult("toolu_bdrk_X", "real result"));

        List<MessageParam> out = HistoryScrubber.scrub(hist);
        // 应该过滤 idx 3 的孤儿 message,剩 5 条
        assertEquals(5, out.size());
        // 后面那对 toolu_bdrk_X 必须保留
        boolean stillHasUse = out.stream().anyMatch(m -> {
            if (!(m.getContent() instanceof List<?> blocks)) return false;
            return blocks.stream().anyMatch(b -> b instanceof ToolUseBlock);
        });
        assertTrue(stillHasUse);
    }
}
