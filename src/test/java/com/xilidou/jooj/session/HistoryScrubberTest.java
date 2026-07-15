package com.xilidou.jooj.session;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRole;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmThinking;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolResult;
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
 *   <li>孤儿 tool_call(头部孤儿)同样被过滤</li>
 *   <li>LlmText / LlmThinking / 其他 block 不被动</li>
 *   <li>无变化时返回原引用(避免无谓拷贝)</li>
 * </ul>
 *
 * <p>P2 Step G2b:整体切到 canonical LlmMessage / LlmToolCall / LlmToolResult。
 */
class HistoryScrubberTest {

    private static LlmMessage userText(String s) {
        return LlmMessage.userText(s);
    }

    private static LlmMessage assistantTextBlocks(String text) {
        return LlmMessage.assistant(List.of(new LlmText(text)));
    }

    private static LlmMessage assistantToolUse(String id) {
        return LlmMessage.assistant(
                List.of(new LlmToolCall(id, "test", JsonNodeFactory.instance.objectNode())));
    }

    private static LlmMessage userToolResult(String id, String content) {
        return LlmMessage.toolResults(new ArrayList<>(
                List.of(LlmToolResult.success(id, content))));
    }

    /** 抽 message 里所有 LlmText 拼接文本(断言用)。 */
    private static String textOf(LlmMessage m) {
        if (m.getContent() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (LlmContent c : m.getContent()) {
            if (c instanceof LlmText t && t.getText() != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(t.getText());
            }
        }
        return sb.toString();
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
        List<LlmMessage> hist = new ArrayList<>();
        hist.add(userText("你好"));
        hist.add(assistantTextBlocks("hi"));
        hist.add(userText("再见"));
        List<LlmMessage> out = HistoryScrubber.scrub(hist);
        assertSame(hist, out, "无 tool 块时应返回原引用避免无谓拷贝");
    }

    @Test
    @DisplayName("正常配对 history 不动")
    void well_formed_pairs_unchanged() {
        List<LlmMessage> hist = new ArrayList<>();
        hist.add(userText("query"));
        hist.add(assistantToolUse("u1"));
        hist.add(userToolResult("u1", "result"));
        hist.add(userText("ok"));
        List<LlmMessage> out = HistoryScrubber.scrub(hist);
        assertEquals(4, out.size());
    }

    @Test
    @DisplayName("孤儿 tool_result(无对应 tool_call)整块被过滤,message 整条丢")
    void orphan_tool_result_dropped() {
        List<LlmMessage> hist = new ArrayList<>();
        hist.add(userText("hi"));
        hist.add(userToolResult("missing_use_id", "ghost"));   // 孤儿
        hist.add(userText("end"));
        List<LlmMessage> out = HistoryScrubber.scrub(hist);
        assertEquals(2, out.size());
        assertEquals("hi", textOf(out.get(0)));
        assertEquals("end", textOf(out.get(1)));
    }

    @Test
    @DisplayName("孤儿 tool_call(无对应 tool_result)同样被过滤")
    void orphan_tool_use_dropped() {
        List<LlmMessage> hist = new ArrayList<>();
        hist.add(userText("hi"));
        hist.add(assistantToolUse("orphan_id"));   // 没人 result 它
        hist.add(userText("end"));
        List<LlmMessage> out = HistoryScrubber.scrub(hist);
        assertEquals(2, out.size());
        // assistant message 整条丢(只有 1 个 tool_call 块,被丢后 empty)
    }

    @Test
    @DisplayName("混合场景:1 对正常 + 1 孤儿 result + 1 孤儿 call")
    void mixed_orphans_and_pairs() {
        List<LlmMessage> hist = new ArrayList<>();
        hist.add(userText("q"));                                      // 0
        hist.add(assistantToolUse("good"));                           // 1
        hist.add(userToolResult("good", "rg"));                       // 2
        hist.add(assistantToolUse("orphan_use"));                     // 3 孤儿(没 result)
        hist.add(userToolResult("orphan_result", "rr"));              // 4 孤儿(没 call)
        hist.add(userText("end"));                                    // 5

        List<LlmMessage> out = HistoryScrubber.scrub(hist);
        // 保留 [0, 1, 2, 5];3 / 4 都丢
        assertEquals(4, out.size());
        assertEquals("q", textOf(out.get(0)));
        assertEquals("end", textOf(out.get(3)));
    }

    @Test
    @DisplayName("一条 TOOL message 多块:好 block 留,坏 block 过滤")
    void per_block_filter_within_one_message() {
        List<LlmMessage> hist = new ArrayList<>();
        hist.add(userText("q"));                                              // 0
        hist.add(assistantToolUse("good"));                                   // 1
        // 一条 TOOL 同时含好 tool_result + 孤儿 tool_result
        // canonical shape:TOOL 消息里不能有 LlmText,所以只装 tool_results;LlmText note 移到独立 USER
        hist.add(new LlmMessage(LlmRole.TOOL, new ArrayList<>(List.of(
                LlmToolResult.success("good", "good-result"),
                LlmToolResult.success("missing", "ghost-result")
        ))));                                                                  // 2
        List<LlmMessage> out = HistoryScrubber.scrub(hist);
        assertEquals(3, out.size(), "整条 message 不应该被丢(还有合法块)");
        List<LlmContent> blocks = out.get(2).getContent();
        assertEquals(1, blocks.size(), "孤儿 tool_result 块应被过滤,只留 good");
        assertTrue(blocks.get(0) instanceof LlmToolResult);
        assertEquals("good", ((LlmToolResult) blocks.get(0)).getToolCallId());
    }

    @Test
    @DisplayName("LlmThinking 不被动 + 同 message 跟 tool_call 共存")
    void thinking_block_untouched() {
        List<LlmMessage> hist = new ArrayList<>();
        hist.add(userText("q"));
        hist.add(LlmMessage.assistant(List.of(
                new LlmThinking("internal", "sig", "anthropic"),
                new LlmText("answer"),
                new LlmToolCall("u1", "test", JsonNodeFactory.instance.objectNode())
        )));
        hist.add(userToolResult("u1", "ok"));
        List<LlmMessage> out = HistoryScrubber.scrub(hist);
        assertEquals(3, out.size());
        List<LlmContent> blocks = out.get(1).getContent();
        assertEquals(3, blocks.size(), "Thinking + Text + ToolCall 全部保留");
        assertTrue(blocks.get(0) instanceof LlmThinking);
    }

    @Test
    @DisplayName("Demo 25 实战 case 复现:[snipped N] 占位符 + 孤儿 tool_result")
    void demo25_real_world_case() {
        List<LlmMessage> hist = new ArrayList<>();
        hist.add(userText("你好"));
        hist.add(assistantTextBlocks("hi back"));
        hist.add(userText("[snipped 3 messages]"));               // 占位符
        hist.add(userToolResult("toolu_bdrk_orphan", "ghost"));   // 真孤儿
        hist.add(assistantToolUse("toolu_bdrk_X"));
        hist.add(userToolResult("toolu_bdrk_X", "real result"));

        List<LlmMessage> out = HistoryScrubber.scrub(hist);
        // 应该过滤 idx 3 的孤儿 message,剩 5 条
        assertEquals(5, out.size());
        // 后面那对 toolu_bdrk_X 必须保留
        boolean stillHasCall = out.stream().anyMatch(m -> {
            if (m.getContent() == null) return false;
            return m.getContent().stream().anyMatch(b -> b instanceof LlmToolCall);
        });
        assertTrue(stillHasCall);
    }

    // ─────────────────────────────────────────────────────────────
    //  s21 Demo 25 副作用 v5:老 placeholder 升级 + 跨边界一致性
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("legacy placeholder 升级到新文案(防加载老 history 后 LLM 看到 'Re-run' 邀请陷入死循环)")
    void scrub_upgrades_legacy_placeholder() {
        // 模拟老 history:1 对完整 (tool_call + tool_result),tool_result output
        // 是老 placeholder
        List<LlmMessage> hist = new ArrayList<>();
        hist.add(userText("q"));
        hist.add(LlmMessage.assistant(List.of(
                new LlmToolCall("u1", "test", JsonNodeFactory.instance.objectNode())
        )));
        LlmToolResult legacy = LlmToolResult.success("u1",
                HistoryScrubber.LEGACY_TOOL_RESULT_PLACEHOLDER);
        hist.add(new LlmMessage(LlmRole.TOOL, new ArrayList<>(List.of(legacy))));

        List<LlmMessage> out = HistoryScrubber.scrub(hist);
        assertEquals(3, out.size(), "完整对应保留");

        // tool_result 的 output 应被升级到新文案
        LlmToolResult outBlock = (LlmToolResult) out.get(2).getContent().get(0);
        assertEquals(HistoryScrubber.NEW_TOOL_RESULT_PLACEHOLDER, outBlock.getOutput(),
                "scrub 应把老 placeholder 升级成新文案");

        // 升级后再 scrub 一次:应是 no-op
        List<LlmMessage> out2 = HistoryScrubber.scrub(out);
        LlmToolResult outBlock2 = (LlmToolResult) out2.get(2).getContent().get(0);
        assertEquals(HistoryScrubber.NEW_TOOL_RESULT_PLACEHOLDER, outBlock2.getOutput(),
                "已升级后再 scrub 应是 no-op");
    }

    @Test
    @DisplayName("HistoryScrubber 跟 MicroCompactor 的 placeholder 文案常量必须一致")
    void placeholder_consistency_across_packages() {
        // session 包不应反向依赖 compact 包,所以两边各自定义同款字面量。
        // 这个测试守门:任一边改了字面量另一边也得跟 — 否则老 history 加载后会出现
        // "scrub 升级到 X,MicroCompactor 不认 X 又当新 longContent 替换" 的灾难。
        assertEquals(
                com.xilidou.jooj.compact.MicroCompactor.PLACEHOLDER,
                HistoryScrubber.NEW_TOOL_RESULT_PLACEHOLDER,
                "MicroCompactor.PLACEHOLDER ↔ HistoryScrubber.NEW_TOOL_RESULT_PLACEHOLDER 必须严格相等");
        assertEquals(
                com.xilidou.jooj.compact.MicroCompactor.LEGACY_PLACEHOLDER,
                HistoryScrubber.LEGACY_TOOL_RESULT_PLACEHOLDER,
                "MicroCompactor.LEGACY_PLACEHOLDER ↔ HistoryScrubber.LEGACY_TOOL_RESULT_PLACEHOLDER 必须严格相等");
    }
}
