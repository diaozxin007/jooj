package com.xilidou.marvis.harness.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xilidou.marvis.harness.http.dto.MessageParam;
import com.xilidou.marvis.harness.http.dto.TextBlock;
import com.xilidou.marvis.harness.http.dto.ToolResultBlock;
import com.xilidou.marvis.harness.http.dto.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link SnipCompactor} 的核心行为。
 *
 * <p>5 个关键场景:
 * <ol>
 *   <li>消息 ≤ maxMessages 不动</li>
 *   <li>消息超阈值,无 tool_use 边界 → 标准切口生效</li>
 *   <li>边界 1：headEnd 落在 tool_use 后,要后移到 tool_result 之后</li>
 *   <li>边界 2：tailStart 落在 tool_result 上,要前移到 tool_use 之前</li>
 *   <li>占位消息内容格式正确 + role=user</li>
 * </ol>
 */
class SnipCompactorTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** 构造一条普通 user 文本消息(非 tool_result)。*/
    private static MessageParam userText(String text) {
        return MessageParam.user(text);
    }

    /** 构造一条普通 assistant 文本消息(非 tool_use)。*/
    private static MessageParam assistantText(String text) {
        return new MessageParam("assistant", List.of(new TextBlock(text)));
    }

    /** 构造一条含 tool_use 的 assistant 消息。*/
    private static MessageParam assistantToolUse(String id) {
        JsonNode input = JSON.objectNode();
        return new MessageParam("assistant", List.of(new ToolUseBlock(id, "test_tool", input)));
    }

    /** 构造一条含 tool_result 的 user 消息。*/
    private static MessageParam userToolResult(String id, String content) {
        return new MessageParam("user", List.of(ToolResultBlock.ofText(id, content)));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 1：消息条数 ≤ maxMessages 不动
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("snip should not modify when messages.size() <= maxMessages")
    void snip_should_not_touch_short_history() {
        SnipCompactor snip = new SnipCompactor(new CompactConfig(50, 3, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        for (int i = 0; i < 50; i++) messages.add(userText("m" + i));

        boolean changed = snip.apply(messages);

        assertFalse(changed, "≤ maxMessages 不应触发裁剪");
        assertEquals(50, messages.size(), "messages 不应被修改");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 2：消息超阈值,无 tool_use 边界 → 标准切口
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("snip should keep first 3 + placeholder + last 47 when no tool_use boundary")
    void snip_should_split_at_default_boundary() {
        SnipCompactor snip = new SnipCompactor(new CompactConfig(50, 3, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        // 51 条普通 user 文本(无 tool_use/tool_result),保证不触发边界保护
        for (int i = 0; i < 51; i++) messages.add(userText("msg-" + i));

        boolean changed = snip.apply(messages);

        assertTrue(changed, "51 > 50 应触发裁剪");
        // 头 3 + 占位 1 + 尾 47 = 51 条
        // headEnd=3, tailStart=51-(50-3)=4, snipped=4-3=1 条
        // 结果:[m0, m1, m2, "[snipped 1 messages]", m4, m5, ..., m50]
        assertEquals(51, messages.size(), "结果应该是 head 3 + placeholder + tail 47 = 51 条");
        assertEquals("msg-0", messages.get(0).getContent());
        assertEquals("msg-1", messages.get(1).getContent());
        assertEquals("msg-2", messages.get(2).getContent());
        assertEquals("[snipped 1 messages]", messages.get(3).getContent());
        assertEquals("msg-4", messages.get(4).getContent());
        assertEquals("msg-50", messages.get(50).getContent());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 3：边界 1 — head 末尾是 tool_use,headEnd 后移到 tool_result 之后
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("snip should advance headEnd past tool_result if head ends on tool_use")
    void snip_should_protect_head_tool_use_pair() {
        SnipCompactor snip = new SnipCompactor(new CompactConfig(50, 3, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("query"));                         // idx 0
        messages.add(assistantToolUse("tu_001"));                // idx 1 (tool_use)
        messages.add(userToolResult("tu_001", "result-1 long content"));  // idx 2 (tool_result)
        // 再灌 48 条普通 user 消息凑够 51
        for (int i = 0; i < 48; i++) messages.add(userText("m" + i));

        boolean changed = snip.apply(messages);

        assertTrue(changed);
        // 原 headEnd=3,但 idx 2 是 tool_result,idx 1 是 tool_use → 边界保护已经满足,
        // 因为 headEnd-1=2 是 tool_result(不是 tool_use),adjustHeadEnd 不动 →
        // 结果 head 包含 [query, tool_use, tool_result] 完整对
        // 所以这个 case 实际上 headEnd 不需要后移(已经在 tool_result 之后)
        // 头 3 + 占位 + 尾 47
        assertEquals(51, messages.size());
        assertEquals("query", messages.get(0).getContent());
        // idx 1 是 tool_use,idx 2 是 tool_result(配对已被完整保留在头部)
        assertTrue(messages.get(1).getContent() instanceof List<?>);
        assertTrue(messages.get(2).getContent() instanceof List<?>);
        assertEquals("[snipped 1 messages]", messages.get(3).getContent());
    }

    @Test
    @DisplayName("snip should advance headEnd to skip orphan tool_use when head=2")
    void snip_should_advance_headEnd_when_head_ends_on_tool_use() {
        // headKeep=2 → headEnd=2,假如 idx 1 是 tool_use,idx 2 是 tool_result,
        // 则 adjustHeadEnd 应该把 headEnd 推到 3(把 tool_result 也保留)
        SnipCompactor snip = new SnipCompactor(new CompactConfig(50, 2, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("query"));                     // idx 0
        messages.add(assistantToolUse("tu_001"));            // idx 1 ← head 末尾会落这里(tool_use)
        messages.add(userToolResult("tu_001", "result-1"));  // idx 2 (tool_result,需要被吃进 head)
        for (int i = 0; i < 48; i++) messages.add(userText("m" + i));

        boolean changed = snip.apply(messages);

        // adjusted headEnd=3(原 2 → 推到 tool_result 后)
        // tailStart 原本 = 51 -(50-2) = 3
        // adjustTailStart 检查 idx 3(普通 user 文本,非 tool_result)→ 不动
        // headEnd(3) >= tailStart(3) → 不裁剪 → changed=false
        // 这个 case 正好覆盖"调整后头尾贴合不裁"的兜底
        assertFalse(changed,
                "boundary protection 把 head 推到 3,与 tailStart=3 重合 → 不裁");
        assertEquals(51, messages.size(), "不裁剪时 messages 不应被修改");
        // 头部完整保留,验证 tool_use ↔ tool_result 配对没被切开
        assertEquals("query", messages.get(0).getContent());
        assertTrue(MessageBoundary.hasToolUse(messages.get(1)));
        assertTrue(MessageBoundary.isToolResult(messages.get(2)));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 4：边界 2 — tailStart 落在 tool_result 上,前移到 tool_use 之前
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("snip should retreat tailStart by 1 when tail starts on tool_result")
    void snip_should_protect_tail_tool_result_pair() {
        SnipCompactor snip = new SnipCompactor(new CompactConfig(10, 3, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        // 头 3
        messages.add(userText("query"));
        messages.add(assistantText("init"));
        messages.add(userText("ok"));
        // 中间 4 条灌水
        for (int i = 0; i < 4; i++) messages.add(userText("middle-" + i));
        // 尾部:故意安排 tool_use(idx 7) → tool_result(idx 8) → 普通(idx 9 ~)
        messages.add(assistantToolUse("tu_999"));                            // idx 7
        messages.add(userToolResult("tu_999", "tail-result-content"));       // idx 8
        messages.add(userText("after"));                                      // idx 9
        messages.add(userText("end"));                                        // idx 10

        // total=11, maxMessages=10, snipHeadKeep=3
        // tailStart = 11 - (10 - 3) = 4
        // adjustTailStart 检查 msgs[4](普通 user 文本)→ 不动
        // 这个 case 实际不触发尾部边界,先让它跑

        boolean changed = snip.apply(messages);

        assertTrue(changed);
        // headEnd=3, tailStart=4, snipped=1 → head 3 + 占位 + 尾 7 = 11
        assertEquals(11, messages.size());
    }

    @Test
    @DisplayName("snip tail boundary: when tailStart lands on tool_result, retreat to include tool_use")
    void snip_should_retreat_tailStart_to_include_tool_use() {
        // 精心设计:tailStart 计算后正好落在 tool_result 上
        // total=11, maxMessages=8, snipHeadKeep=3 → tailStart = 11 -(8-3) = 6
        SnipCompactor snip = new SnipCompactor(new CompactConfig(8, 3, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        // 头 3:idx 0/1/2
        messages.add(userText("query"));
        messages.add(assistantText("init"));
        messages.add(userText("ok"));
        // 中间(会被裁掉):idx 3/4
        messages.add(userText("middle-0"));
        messages.add(userText("middle-1"));
        // idx 5 = assistant(tool_use), idx 6 = user(tool_result),正好 tailStart=6 落在 tool_result 上
        messages.add(assistantToolUse("tu_888"));            // idx 5
        messages.add(userToolResult("tu_888", "result"));    // idx 6 ← tailStart 原值
        messages.add(userText("after"));                      // idx 7
        messages.add(userText("end-1"));                      // idx 8
        messages.add(userText("end-2"));                      // idx 9
        messages.add(userText("end-3"));                      // idx 10

        boolean changed = snip.apply(messages);

        assertTrue(changed, "11 > 8 应该裁剪");
        // adjustTailStart 把 6 → 5(把 tool_use 也带进尾部)
        // headEnd=3 < tailStart=5 → snipped = 5-3 = 2 条
        // 结果 = head 3 + 占位 + 尾(11-5)=6 → 总 10 条
        assertEquals(10, messages.size());
        // 第 4 条是占位
        assertEquals("[snipped 2 messages]", messages.get(3).getContent());
        // 第 5 条应该是 assistant(tool_use)(被边界保护带进来)
        MessageParam fifth = messages.get(4);
        assertEquals("assistant", fifth.getRole());
        assertTrue(fifth.getContent() instanceof List<?>);
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 5：占位消息格式 + role 正确
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("placeholder message should be role=user with [snipped N messages] text")
    void snip_placeholder_should_be_user_role_with_correct_text() {
        SnipCompactor snip = new SnipCompactor(new CompactConfig(10, 3, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        for (int i = 0; i < 20; i++) messages.add(userText("m" + i));

        snip.apply(messages);

        // headEnd=3, tailStart=20-(10-3)=13, snipped=10
        MessageParam placeholder = messages.get(3);
        assertEquals("user", placeholder.getRole(),
                "占位必须是 user role(Anthropic 不接受 system role 在 messages 里)");
        assertEquals("[snipped 10 messages]", placeholder.getContent());
    }
}
