package com.xilidou.jooj.compact;

import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmToolResult;
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
 *   <li>多个 tool_result 在同一个 TOOL message 里 → 都按时间序处理</li>
 * </ol>
 *
 * <p>P2 Step G:fixture 迁到 canonical {@link LlmMessage / LlmToolResult}(TOOL 一等 role,
 * output 字段替代 wire content)。
 */
class MicroCompactorTest {

    /** 构造一条只含一个 tool_result 的 TOOL message。*/
    private static LlmMessage userToolResult(String id, String content) {
        return LlmMessage.toolResults(new ArrayList<>(List.of(LlmToolResult.success(id, content))));
    }

    /** 构造一条含多个 tool_result 的 TOOL message(并行 tool_call 场景)。*/
    private static LlmMessage userMultipleToolResults(List<LlmToolResult> results) {
        return LlmMessage.toolResults(new ArrayList<>(results));
    }

    /** 从 messages[i] 里取第 0 个 LlmToolResult(所有 TOOL 消息 fixture 都保证 content 首块是 result)。*/
    private static LlmToolResult firstToolResult(LlmMessage m) {
        return (LlmToolResult) m.getContent().get(0);
    }

    /** 从 messages[i] 里取第 blockIdx 个 LlmToolResult。 */
    private static LlmToolResult toolResultAt(LlmMessage m, int blockIdx) {
        return (LlmToolResult) m.getContent().get(blockIdx);
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
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userText("query"));
        messages.add(userToolResult("tu_1", longContent(200)));
        messages.add(userToolResult("tu_2", longContent(200)));
        messages.add(userToolResult("tu_3", longContent(200)));  // 共 3 个 = keepRecent

        boolean changed = micro.apply(messages);

        assertFalse(changed, "数量等于 keepRecent 不应触发占位");
        // 内容原封不动
        assertEquals(longContent(200), firstToolResult(messages.get(1)).getOutput());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 2：5 个 tool_result,前 2 个长内容 → 替换,后 3 个保留
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("micro should compact older results, keep last keepRecent intact")
    void micro_should_compact_older_keep_recent() {
        MicroCompactor micro = new MicroCompactor(new CompactConfig(50, 3, 3, 120));
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userText("query"));
        for (int i = 0; i < 5; i++) {
            messages.add(userToolResult("tu_" + i, longContent(200)));
        }

        boolean changed = micro.apply(messages);

        assertTrue(changed, "5 > 3 应该压缩前 2 个");
        // 前 2 个被占位
        assertEquals(MicroCompactor.PLACEHOLDER, firstToolResult(messages.get(1)).getOutput());
        assertEquals(MicroCompactor.PLACEHOLDER, firstToolResult(messages.get(2)).getOutput());
        // 后 3 个保留原文
        assertEquals(longContent(200), firstToolResult(messages.get(3)).getOutput());
        assertEquals(longContent(200), firstToolResult(messages.get(4)).getOutput());
        assertEquals(longContent(200), firstToolResult(messages.get(5)).getOutput());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 3：短内容不替换(避免占位符比原文还长)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("micro should not replace content shorter than minPlaceholderLen")
    void micro_should_skip_short_content() {
        MicroCompactor micro = new MicroCompactor(new CompactConfig(50, 3, 3, 120));
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userText("query"));
        // 第 0/1 个本应被压缩,但内容很短,不替换
        messages.add(userToolResult("tu_0", "short"));         // 5 字符
        messages.add(userToolResult("tu_1", "OK"));             // 2 字符
        messages.add(userToolResult("tu_2", longContent(200)));
        messages.add(userToolResult("tu_3", longContent(200)));
        messages.add(userToolResult("tu_4", longContent(200)));

        boolean changed = micro.apply(messages);

        assertFalse(changed, "前 2 个内容都太短,不该有任何替换");
        assertEquals("short", firstToolResult(messages.get(1)).getOutput());
        assertEquals("OK", firstToolResult(messages.get(2)).getOutput());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 4：幂等性 — 已经是占位符的不重复处理
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("micro should be idempotent: already-placeholder content not re-replaced")
    void micro_should_be_idempotent() {
        MicroCompactor micro = new MicroCompactor(new CompactConfig(50, 3, 3, 120));
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userText("query"));
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
    //  测试 5:同一 TOOL message 里多个 tool_result 都按时间序处理
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("micro should handle multiple tool_results in the same TOOL message")
    void micro_should_compact_within_one_user_message() {
        MicroCompactor micro = new MicroCompactor(new CompactConfig(50, 3, 3, 120));
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userText("query"));
        // 一条 TOOL message 包含 5 个 tool_result(并行 tool_call 场景)
        List<LlmToolResult> blocks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            blocks.add(LlmToolResult.success("tu_" + i, longContent(200)));
        }
        messages.add(userMultipleToolResults(blocks));

        boolean changed = micro.apply(messages);

        assertTrue(changed);
        // collectToolResults 按列表内顺序收集,前 2 个被替换,后 3 个保留
        LlmMessage tool = messages.get(1);
        assertEquals(MicroCompactor.PLACEHOLDER, toolResultAt(tool, 0).getOutput());
        assertEquals(MicroCompactor.PLACEHOLDER, toolResultAt(tool, 1).getOutput());
        assertEquals(longContent(200), toolResultAt(tool, 2).getOutput());
        assertEquals(longContent(200), toolResultAt(tool, 3).getOutput());
        assertEquals(longContent(200), toolResultAt(tool, 4).getOutput());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 6:跨多 TOOL 消息时间序统一处理
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("micro orders results by message timeline across TOOL messages")
    void micro_should_use_global_timeline_across_messages() {
        MicroCompactor micro = new MicroCompactor(new CompactConfig(50, 3, 2, 120));
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userText("q"));
        messages.add(userToolResult("tu_0", longContent(150)));
        messages.add(userToolResult("tu_1", longContent(150)));
        messages.add(userToolResult("tu_2", longContent(150)));
        messages.add(userToolResult("tu_3", longContent(150)));

        boolean changed = micro.apply(messages);

        assertTrue(changed);
        // keepRecent=2 → 保留最后 2 个(tu_2, tu_3),前 2 个(tu_0, tu_1)被替换
        assertEquals(MicroCompactor.PLACEHOLDER, firstToolResult(messages.get(1)).getOutput());
        assertEquals(MicroCompactor.PLACEHOLDER, firstToolResult(messages.get(2)).getOutput());
        assertEquals(longContent(150), firstToolResult(messages.get(3)).getOutput());
        assertEquals(longContent(150), firstToolResult(messages.get(4)).getOutput());
    }

    // ─────────────────────────────────────────────────────────────
    //  s21 Demo 25 副作用 v5:placeholder 文案 + 反诱导循环
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PLACEHOLDER 文案明确指示 LLM 不要重跑 (反死循环邀请函)")
    void placeholder_text_does_not_invite_rerun() {
        // 关键不变量:placeholder 必须含显式禁止重跑的措辞
        // (跟老文案 "Re-run the tool if needed" 形成对比 — 那条是死循环邀请函)
        String p = MicroCompactor.PLACEHOLDER;
        assertTrue(p.toLowerCase().contains("do not") || p.toLowerCase().contains("don't"),
                "PLACEHOLDER 必须显式禁止重跑(用 'Do NOT' / 'Don't')。实际:" + p);
        // 兜底:不能含老文案的"Re-run if needed"措辞
        assertFalse(p.toLowerCase().contains("re-run the tool if needed"),
                "PLACEHOLDER 不能含老文案 'Re-run the tool if needed'(死循环邀请函)。实际:" + p);
        // 兜底 2:LEGACY_PLACEHOLDER 字面值跟新 PLACEHOLDER 必须不同
        assertNotEquals(MicroCompactor.LEGACY_PLACEHOLDER, p,
                "PLACEHOLDER 跟 LEGACY_PLACEHOLDER 必须是两个字面值,否则 idempotent check 退化");
    }

    @Test
    @DisplayName("LEGACY_PLACEHOLDER 仍被识别为已压缩(防 jooj 重启加载老 history 后无限替换)")
    void legacy_placeholder_idempotent() {
        // 给 LlmToolResult 直接塞老文案,模拟磁盘上残留的老 history
        MicroCompactor compactor = new MicroCompactor(new CompactConfig(50, 3, 1, 50));
        List<LlmMessage> messages = new ArrayList<>();
        // 4 个 TOOL 各带一个老 placeholder 的 tool_result
        for (int i = 0; i < 4; i++) {
            messages.add(LlmMessage.toolResults(new ArrayList<>(List.of(
                    LlmToolResult.success("tu_" + i, MicroCompactor.LEGACY_PLACEHOLDER)
            ))));
        }
        boolean changed = compactor.apply(messages);
        // 老 placeholder 都不应被再次替换(idempotent)
        assertFalse(changed, "已是老 placeholder 不应再触发替换 - 防加载老 history 后无限替换");
        for (LlmMessage m : messages) {
            assertEquals(MicroCompactor.LEGACY_PLACEHOLDER, firstToolResult(m).getOutput(),
                    "老 placeholder 应保持不变。如需升级走 HistoryScrubber 路径");
        }
    }

    @Test
    @DisplayName("二次 apply 不重复替换 (新 PLACEHOLDER 自身幂等)")
    void new_placeholder_idempotent() {
        MicroCompactor compactor = new MicroCompactor(new CompactConfig(50, 3, 1, 50));
        List<LlmMessage> messages = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            messages.add(LlmMessage.toolResults(new ArrayList<>(List.of(
                    LlmToolResult.success("tu_" + i, "x".repeat(200))
            ))));
        }

        boolean first = compactor.apply(messages);
        assertTrue(first, "第一次 apply 应替换 3 个 (4 - keepRecent=1)");
        boolean second = compactor.apply(messages);
        assertFalse(second, "第二次 apply 看到的全是 PLACEHOLDER 应跳过 - 这是反诱导死循环的最后一道防线");
    }
}
