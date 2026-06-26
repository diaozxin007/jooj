package com.xilidou.jooj.compact;

import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link MicroCompactor} 的核心行为。
 *
 * <p>5 个关键场景:
 * <ol>
 *   <li>tool_result ≤ keepRecent 不动</li>
 *   <li>5 个 tool_result, 前 2 个长内容 → 替换;后 3 个保留</li>
 *   <li>短内容(≤ minPlaceholderLen)不替换(微小输出不浪费 token)</li>
 *   <li>已经是占位符的不重复替换(幂等)</li>
 *   <li>多个 tool_result 在同一个 user message 里 → 都按时间序处理</li>
 * </ol>
 */
class MicroCompactorTest {

    /** 构造一条只含一个 tool_result 的 user message。*/
    private static MessageParam userToolResult(String id, String content) {
        return new MessageParam("user", new ArrayList<>(List.of(ToolResultBlock.ofText(id, content))));
    }

    /** 构造一条含多个 tool_result 的 user message(并行 tool_use 场景)。*/
    private static MessageParam userMultipleToolResults(List<ToolResultBlock> results) {
        return new MessageParam("user", new ArrayList<>(results));
    }

    /** 生成一段长内容(确保 > 120 chars)。*/
    private static String longContent(int len) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < len) sb.append("xxxx ");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 1：tool_result 数量 ≤ keepRecent 不动
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("micro should not modify when tool_results count <= keepRecent")
    void micro_should_not_touch_few_results() {
        MicroCompactor micro = new MicroCompactor(new CompactConfig(50, 3, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("query"));
        messages.add(userToolResult("tu_1", longContent(200)));
        messages.add(userToolResult("tu_2", longContent(200)));
        messages.add(userToolResult("tu_3", longContent(200)));  // 共 3 个 = keepRecent

        boolean changed = micro.apply(messages);

        assertFalse(changed, "数量等于 keepRecent 不应触发占位");
        // 内容原封不动
        ToolResultBlock first = (ToolResultBlock) ((List<?>) messages.get(1).getContent()).get(0);
        assertEquals(longContent(200), first.getContent());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 2：5 个 tool_result,前 2 个长内容 → 替换,后 3 个保留
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("micro should compact older results, keep last keepRecent intact")
    void micro_should_compact_older_keep_recent() {
        MicroCompactor micro = new MicroCompactor(new CompactConfig(50, 3, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("query"));
        for (int i = 0; i < 5; i++) {
            messages.add(userToolResult("tu_" + i, longContent(200)));
        }

        boolean changed = micro.apply(messages);

        assertTrue(changed, "5 > 3 应该压缩前 2 个");
        // 前 2 个被占位
        ToolResultBlock r0 = (ToolResultBlock) ((List<?>) messages.get(1).getContent()).get(0);
        ToolResultBlock r1 = (ToolResultBlock) ((List<?>) messages.get(2).getContent()).get(0);
        assertEquals(MicroCompactor.PLACEHOLDER, r0.getContent());
        assertEquals(MicroCompactor.PLACEHOLDER, r1.getContent());
        // 后 3 个保留原文
        ToolResultBlock r2 = (ToolResultBlock) ((List<?>) messages.get(3).getContent()).get(0);
        ToolResultBlock r3 = (ToolResultBlock) ((List<?>) messages.get(4).getContent()).get(0);
        ToolResultBlock r4 = (ToolResultBlock) ((List<?>) messages.get(5).getContent()).get(0);
        assertEquals(longContent(200), r2.getContent());
        assertEquals(longContent(200), r3.getContent());
        assertEquals(longContent(200), r4.getContent());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 3：短内容不替换(避免占位符比原文还长)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("micro should not replace content shorter than minPlaceholderLen")
    void micro_should_skip_short_content() {
        MicroCompactor micro = new MicroCompactor(new CompactConfig(50, 3, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("query"));
        // 第 0/1 个本应被压缩,但内容很短,不替换
        messages.add(userToolResult("tu_0", "short"));         // 5 字符
        messages.add(userToolResult("tu_1", "OK"));             // 2 字符
        messages.add(userToolResult("tu_2", longContent(200)));
        messages.add(userToolResult("tu_3", longContent(200)));
        messages.add(userToolResult("tu_4", longContent(200)));

        boolean changed = micro.apply(messages);

        assertFalse(changed, "前 2 个内容都太短,不该有任何替换");
        ToolResultBlock r0 = (ToolResultBlock) ((List<?>) messages.get(1).getContent()).get(0);
        ToolResultBlock r1 = (ToolResultBlock) ((List<?>) messages.get(2).getContent()).get(0);
        assertEquals("short", r0.getContent());
        assertEquals("OK", r1.getContent());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 4：幂等性 — 已经是占位符的不重复处理
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("micro should be idempotent: already-placeholder content not re-replaced")
    void micro_should_be_idempotent() {
        MicroCompactor micro = new MicroCompactor(new CompactConfig(50, 3, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("query"));
        for (int i = 0; i < 5; i++) {
            messages.add(userToolResult("tu_" + i, longContent(200)));
        }

        // 第一次 apply 应该改 2 个
        assertTrue(micro.apply(messages));
        // 第二次 apply,前 2 个已经是占位符,不该再触发"changed=true"
        assertFalse(micro.apply(messages),
                "第二次 apply 应返回 false:前 2 个已是占位符,后 3 个仍在 keepRecent 内");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 5:同一 user message 里多个 tool_result 都按时间序处理
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("micro should handle multiple tool_results in the same user message")
    void micro_should_compact_within_one_user_message() {
        MicroCompactor micro = new MicroCompactor(new CompactConfig(50, 3, 3, 120));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("query"));
        // 一条 user message 包含 5 个 tool_result(并行 tool_use 场景)
        List<ToolResultBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            blocks.add(ToolResultBlock.ofText("tu_" + i, longContent(200)));
        }
        messages.add(userMultipleToolResults(blocks));

        boolean changed = micro.apply(messages);

        assertTrue(changed);
        // collectToolResults 按列表内顺序收集,前 2 个被替换,后 3 个保留
        @SuppressWarnings("unchecked")
        List<ToolResultBlock> stored =
                (List<ToolResultBlock>) messages.get(1).getContent();
        assertEquals(MicroCompactor.PLACEHOLDER, stored.get(0).getContent());
        assertEquals(MicroCompactor.PLACEHOLDER, stored.get(1).getContent());
        assertEquals(longContent(200), stored.get(2).getContent());
        assertEquals(longContent(200), stored.get(3).getContent());
        assertEquals(longContent(200), stored.get(4).getContent());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 6:跨多 user 消息时间序统一处理
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("micro orders results by message timeline across user messages")
    void micro_should_use_global_timeline_across_messages() {
        MicroCompactor micro = new MicroCompactor(new CompactConfig(50, 3, 2, 120));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("q"));
        messages.add(userToolResult("tu_0", longContent(150)));
        messages.add(userToolResult("tu_1", longContent(150)));
        messages.add(userToolResult("tu_2", longContent(150)));
        messages.add(userToolResult("tu_3", longContent(150)));

        boolean changed = micro.apply(messages);

        assertTrue(changed);
        // keepRecent=2 → 保留最后 2 个(tu_2, tu_3),前 2 个(tu_0, tu_1)被替换
        ToolResultBlock r0 = (ToolResultBlock) ((List<?>) messages.get(1).getContent()).get(0);
        ToolResultBlock r1 = (ToolResultBlock) ((List<?>) messages.get(2).getContent()).get(0);
        ToolResultBlock r2 = (ToolResultBlock) ((List<?>) messages.get(3).getContent()).get(0);
        ToolResultBlock r3 = (ToolResultBlock) ((List<?>) messages.get(4).getContent()).get(0);
        assertEquals(MicroCompactor.PLACEHOLDER, r0.getContent());
        assertEquals(MicroCompactor.PLACEHOLDER, r1.getContent());
        assertEquals(longContent(150), r2.getContent());
        assertEquals(longContent(150), r3.getContent());
    }
}
