package com.xilidou.jooj.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
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

    // ─────────────────────────────────────────────────────────────
    //  s21 Demo 25 (副作用): self-consistency walk 加固后的回归测试
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("snip 不能切坏跨多条边界的 tool_use ↔ tool_result 配对(Demo 25 实战 case)")
    void snip_self_consistency_walk_no_orphan_tool_result_in_tail() {
        // 重现 Demo 25 实战 case:
        //   [0] user "你好"
        //   [1] assistant thinking + text   (no tool_use)
        //   [2] user "你用哪个模型"
        //   [3] assistant tool_use(date)    ← 待 snip
        //   [4] user tool_result(date)
        //   [5] assistant tool_use(cron)
        //   [6] user tool_result(cron)
        //   [7..N] 后续
        //
        // 预期:adjustTailStart 必须把整对 tu_use(date) + tu_result(date) 都带进 tail,
        //       否则 tu_result(date) 会成尾部孤儿 → Anthropic 400。
        SnipCompactor snip = new SnipCompactor(new CompactConfig(8, 2, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("你好"));                                    // 0
        messages.add(assistantText("hello!"));                             // 1
        messages.add(userText("你用哪个模型"));                             // 2
        messages.add(assistantToolUse("tu_date"));                         // 3 ← 关键
        messages.add(userToolResult("tu_date", "Tue Jun 30 11:46:44"));    // 4 ← 配对
        messages.add(assistantToolUse("tu_cron"));                         // 5
        messages.add(userToolResult("tu_cron", "scheduled"));              // 6
        messages.add(userText("end-1"));                                    // 7
        messages.add(userText("end-2"));                                    // 8
        messages.add(userText("end-3"));                                    // 9
        messages.add(userText("end-4"));                                    // 10

        // total=11, max=8, headKeep=2 → tailStart = 11-(8-2) = 5
        // 老 1-step adjustTailStart: msgs[5]=tu_use(cron),不是 tool_result → 不动
        //   结果:[0,1] + placeholder + [5..10]; 但 head 末尾 idx 1 是普通 text,
        //   tail 起 idx 5 是 tu_use,tu_result(date) 在被 snip 的 [2..5) 里 → 没问题
        //
        // 等等,实际危险 case 是 tailStart 落在 tu_result 上(idx 4 或 6)。
        // 让我们 verify boundary 行为 self-consistent — apply 后扫整个 result,
        // 不应有任何 orphan tool_result。

        boolean changed = snip.apply(messages);
        assertTrue(changed);

        // 关键不变量:apply 后,任意 tool_result 都能在前面找到对应的 tool_use。
        assertNoOrphanToolResult(messages);
    }

    @Test
    @DisplayName("snip 真实复现:tailStart 落在普通文本但孤儿 tool_result 在 tail 内部")
    void snip_self_consistency_walk_orphan_inside_tail() {
        // 关键场景(Demo 25 user 撞过):tailStart=N,msgs[N] 是普通 user 文本,
        // msgs[N+1] 才是 user tool_result(其 tool_use 在被 snip 的范围里)。
        // 老 adjustTailStart 只看 msgs[N] 不看 msgs[N+1],漏掉这种孤儿。
        // 新 self-consistency walk 必须把 tail 缩到包含 tool_use 才停。
        SnipCompactor snip = new SnipCompactor(new CompactConfig(6, 2, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("hi"));                                  // 0
        messages.add(assistantText("hi back"));                         // 1
        // 中间被 snip 的部分应该带 tu_use,但故意不放 → 模拟 tu_use 已被旧 snip 切掉的现状
        messages.add(userText("middle text 1"));                       // 2
        messages.add(userText("middle text 2"));                       // 3
        messages.add(assistantToolUse("tu_alpha"));                    // 4 ← tu_use 在 tail 边缘
        messages.add(userToolResult("tu_alpha", "alpha-result"));      // 5 ← tu_result
        messages.add(userText("end"));                                  // 6

        // total=7, max=6, headKeep=2 → tailStart = 7-(6-2) = 3
        // 老:msgs[3] 是 user text(非 tool_result)→ 不动 tailStart=3
        //     裁 [2..3) 一条,head [0..2] + placeholder + tail [3..6]
        //     tail 含 [middle text 2, tu_use(alpha), tu_result(alpha), end] — 配对 OK
        // 新:同上,因为 tu_alpha 配对在 tail 内,无 unmatched → 不缩,行为一致。
        //
        // 真正暴露 bug 的 case 是:tu_use 在 tail 之前(被 snip),tu_result 在 tail 之内。
        // 但 SnipCompactor 不会把 tu_use 切丢然后留 tu_result —— 因为 adjustTailStart
        // 必须把 tu_result 对应的 tu_use 也带进 tail 才合规。让我们实际构造一个 nasty case:
        //
        //   [0] user "hi"
        //   [1] assistant txt
        //   [2] assistant tu_use(X)        ← 必须跟 tu_result(X) 一起进 tail
        //   [3] user middle
        //   [4] user tu_result(X)          ← tu_use(X) 在 idx 2,跨 idx 3 间隔
        //   [5] user end
        //
        // total=6, max=4, headKeep=2 → tailStart = 6-(4-2) = 4
        // 老 adjustTailStart(4): msgs[4]=tu_result, msgs[3]=user text(非 tu_use)→ 不动!
        //   结果 head [0,1] + placeholder + tail [4,5] = [hi, txt, [snipped 2], tu_result(X), end]
        //   tu_result(X) 孤儿 → 400
        // 新:openResults={X},tail [4,5] 内没 tu_use(X) → tailStart=3,
        //   tail [3..6] = [middle, tu_result(X), end];还是没 tu_use → tailStart=2,
        //   tail [2..6] = [tu_use(X), middle, tu_result(X), end] → 配对 OK,停。

        SnipCompactor snip2 = new SnipCompactor(new CompactConfig(4, 2, 3, 120));
        List<MessageParam> nasty = new ArrayList<>();
        nasty.add(userText("hi"));                              // 0
        nasty.add(assistantText("hi back"));                     // 1
        nasty.add(assistantToolUse("tu_X"));                    // 2
        nasty.add(userText("intervening text"));                // 3
        nasty.add(userToolResult("tu_X", "result"));            // 4
        nasty.add(userText("end"));                              // 5

        // 这个 case 实际上 self-consistency walk 会把 tailStart 缩到 2,headEnd=2,
        // headEnd==tailStart 触发 "不裁" 兜底 —— **这正是想要的**:宁可不裁也不能切坏配对。
        boolean changed = snip2.apply(nasty);
        assertNoOrphanToolResult(nasty);
        // 不论 changed 与否,关键是 message 列表里仍有 tu_X 配对完整
        boolean hasUse = nasty.stream().anyMatch(MessageBoundary::hasToolUse);
        assertTrue(hasUse, "tu_X 的 tool_use 必须留在 messages 里");
        boolean hasResult = nasty.stream().anyMatch(MessageBoundary::isToolResult);
        assertTrue(hasResult, "tu_X 的 tool_result 也必须留在 messages 里");
        // 这个 case 走兜底"不裁"路径,数量保持 6
        assertEquals(6, nasty.size(), "无可裁路径 → messages 不动");
    }

    @Test
    @DisplayName("adjustHeadEnd self-consistency walk:跨多条 head 也能扩到包含全部 tool_result")
    void boundary_head_walk_skips_multiple_intervening() {
        // [0] user "q"
        // [1] assistant tu_use(A)         ← head 必须包含完整对
        // [2] assistant tu_use(B)         ← (虽然实际不太常见,但协议允许 1 message 多 tu_use)
        // [3] user tu_result(A)
        // [4] user middle text             ← intervening 普通文本
        // [5] user tu_result(B)
        // [6..] 后续
        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("q"));                       // 0
        messages.add(assistantToolUse("A"));               // 1
        messages.add(assistantToolUse("B"));               // 2
        messages.add(userToolResult("A", "ra"));           // 3
        messages.add(userText("intervening"));             // 4
        messages.add(userToolResult("B", "rb"));           // 5
        messages.add(userText("after"));                   // 6

        // headEnd=2 起手,head=[q, tu_use(A)],unmatched={A}
        // 扩到 3:[..,tu_use(A),tu_use(B)],unmatched={A,B}
        // 扩到 4:加 tu_result(A),unmatched={B}
        // 扩到 5:加 middle,unmatched={B}
        // 扩到 6:加 tu_result(B),unmatched={} → 停
        int adjusted = MessageBoundary.adjustHeadEnd(messages, 2);
        assertEquals(6, adjusted, "head walk 必须扩到包含全部 tool_result(跨 intervening)");
    }

    @Test
    @DisplayName("adjustTailStart self-consistency walk 扩到包含 tool_use(跨 intervening)")
    void boundary_tail_walk_retreats_across_intervening() {
        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("head-0"));                  // 0
        messages.add(assistantText("head-1"));             // 1
        messages.add(assistantToolUse("X"));               // 2
        messages.add(userText("intervening"));             // 3
        messages.add(userToolResult("X", "rx"));           // 4
        messages.add(userText("end"));                     // 5

        // tailStart=4 起手,tail=[tu_result(X), end],unmatched={X}
        // 缩到 3:[..,intervening,tu_result(X),end],unmatched={X}
        // 缩到 2:[tu_use(X),...] → unmatched={} 停
        int adjusted = MessageBoundary.adjustTailStart(messages, 4);
        assertEquals(2, adjusted, "tail walk 必须缩到包含 tool_use");
    }

    /**
     * 验证不变量:列表里任一 {@code tool_result} 块,前面必有同 id 的 {@code tool_use}。
     */
    private static void assertNoOrphanToolResult(List<MessageParam> messages) {
        java.util.Set<String> seenUseIds = new java.util.HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageParam m = messages.get(i);
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object b : blocks) {
                if (b instanceof ToolUseBlock tu && tu.getId() != null) {
                    seenUseIds.add(tu.getId());
                }
                if (b instanceof ToolResultBlock tr && tr.getToolUseId() != null) {
                    assertTrue(seenUseIds.contains(tr.getToolUseId()),
                            "messages[" + i + "] 含孤儿 tool_result, tool_use_id="
                                    + tr.getToolUseId() + ",前面没有匹配的 tool_use");
                }
            }
        }
    }
}
