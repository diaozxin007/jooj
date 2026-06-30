package com.xilidou.jooj.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CompactPipeline} 纯单元测试。
 *
 * <p>覆盖:
 * <ol>
 *   <li>L1 + L2 同时触发的端到端场景</li>
 *   <li>顺序验证:snip 之后 micro 看到的 tool_result 数量已经减少</li>
 *   <li>L3 (budget) 与 L1/L2 串联 — 大 tool_result 落盘后 L1/L2 看到的是 stub</li>
 *   <li>L3 + L1 + L2 三层叠加 — 巨型历史的最大压缩力度</li>
 * </ol>
 *
 * <p>切片 C 之后:这些是真正的"单元"测试 —— 直接 new CompactPipeline 跑,
 * 不涉及 Spring 容器,不需要 AgentLoopHarness。Loop 与 Compact 集成由
 * {@code JoojSpringIntegrationTest} 保障。
 */
class CompactPipelineTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private static MessageParam userText(String text) {
        return MessageParam.user(text);
    }

    private static MessageParam assistantText(String text) {
        return new MessageParam("assistant", List.of(new TextBlock(text)));
    }

    @SuppressWarnings("unused")
    private static MessageParam assistantToolUse(String id) {
        JsonNode input = JSON.objectNode();
        return new MessageParam("assistant", List.of(new ToolUseBlock(id, "test_tool", input)));
    }

    private static MessageParam userToolResult(String id, String content) {
        return new MessageParam("user", new ArrayList<>(List.of(ToolResultBlock.ofText(id, content))));
    }

    private static String longContent(int len) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < len) sb.append("xxxx ");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 1:L1 + L2 同时触发
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pipeline should trigger both L1 snip and L2 micro on long history")
    void pipeline_should_apply_both_layers() {
        CompactPipeline pipeline = new CompactPipeline(new CompactConfig(8, 2, 2, 50));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("query"));
        messages.add(assistantText("ok"));
        // s21 Demo 25:fixture 改成成对 (tool_use, tool_result),让 boundary walk
        // 不会因孤儿 tool_result 触发 "不裁" 兜底。
        for (int i = 0; i < 12; i++) {
            messages.add(assistantToolUse("tu_" + i));
            messages.add(userToolResult("tu_" + i, longContent(200)));
        }

        boolean changed = pipeline.apply(messages);

        assertTrue(changed, "L1 或 L2 至少有一层应该触发");
        // total=2+24=26, max=8, headKeep=2 → tailStart=20. boundary 不动.
        // snipped=18, result = head 2 + placeholder + tail 6 = 9
        assertEquals(9, messages.size(), "L1 snip 后总 9 条");

        assertEquals("user", messages.get(2).getRole());
        assertEquals("[snipped 18 messages]", messages.get(2).getContent());

        // tail 6 条 = 3 对 (tu_9_use, tu_9_result, tu_10_use, tu_10_result, tu_11_use, tu_11_result)
        // 含 3 个 tool_result, keepRecent=2 → L2 替换 1 个
        int placeholderCount = 0;
        for (MessageParam m : messages) {
            if (m.getContent() instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof ToolResultBlock trb &&
                            MicroCompactor.PLACEHOLDER.equals(trb.getContent())) {
                        placeholderCount++;
                    }
                }
            }
        }
        assertEquals(1, placeholderCount, "L2 应替换 3-2=1 个 tool_result");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 2:顺序验证 — snip 先于 micro
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("snip should run before micro: micro sees fewer tool_results after snip")
    void pipeline_order_snip_before_micro() {
        CompactPipeline pipeline = new CompactPipeline(new CompactConfig(6, 1, 2, 50));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("query"));
        // s21 Demo 25:成对 fixture
        for (int i = 0; i < 8; i++) {
            messages.add(assistantToolUse("tu_" + i));
            messages.add(userToolResult("tu_" + i, longContent(150)));
        }

        pipeline.apply(messages);

        // total=1+16=17, max=6, headKeep=1 → tailStart=12.
        // tail [12..17)=5 条 (tu_5_result, tu_6_use, tu_6_result, tu_7_use, tu_7_result),
        // openResults={5,6,7},tu_use 6,7 配对,tu_5 unmatched → 退到 11.
        // tail [11..17) 6 条 = 3 对配对 → 停。snipped=11-1=10
        // result = head 1 + placeholder + tail 6 = 8
        assertEquals(8, messages.size());
        assertEquals("[snipped 10 messages]", messages.get(1).getContent());

        // tail 6 条 = 3 对 → 3 个 tool_result, keepRecent=2 → replace 1
        int placeholderCount = 0;
        for (int i = 2; i < messages.size(); i++) {
            if (messages.get(i).getContent() instanceof List<?> blocks
                    && !blocks.isEmpty()
                    && blocks.get(0) instanceof ToolResultBlock trb
                    && MicroCompactor.PLACEHOLDER.equals(trb.getContent())) {
                placeholderCount++;
            }
        }
        assertEquals(1, placeholderCount, "L2 在 snip 之后看到 3 个 tool_result, 替换 1 个");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 3:L3 (budget) 与 L1/L2 串联 — 大 tool_result 落盘后 L1/L2 看到的是 stub
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pipeline order: L3 persists large tool_results before L1/L2 see them")
    void pipeline_L3_runs_before_L1_L2(@TempDir Path tempDir) throws Exception {
        CompactConfig config = new CompactConfig(8, 2, 2, 50, 100, tempDir);
        CompactPipeline pipeline = new CompactPipeline(config);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("query"));
        messages.add(assistantText("ok"));
        for (int i = 0; i < 6; i++) {
            messages.add(userToolResult("tu_" + i, longContent(500)));
        }

        boolean changed = pipeline.apply(messages);

        assertTrue(changed);

        for (int i = 0; i < 6; i++) {
            assertTrue(Files.exists(tempDir.resolve("tu_" + i + ".txt")),
                    "L3 应在 L1 删之前先落盘 tu_" + i);
        }

        for (MessageParam m : messages) {
            if (m.getContent() instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof ToolResultBlock trb) {
                        String s = String.valueOf(trb.getContent());
                        assertNotEquals(MicroCompactor.PLACEHOLDER, s,
                                "L3 stub 不应被 L2 替换为 PLACEHOLDER (前缀检查失效?)");
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 4:L3 + L1 + L2 三层叠加 — 巨型历史的最大压缩力度
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pipeline: huge history triggers L3 + L1 + L2 simultaneously")
    void pipeline_three_layers_combined(@TempDir Path tempDir) {
        CompactConfig config = new CompactConfig(10, 2, 2, 50, 200, tempDir);
        CompactPipeline pipeline = new CompactPipeline(config);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("query"));
        messages.add(assistantText("ok"));
        // s21 Demo 25:成对 fixture(每个 result 都有对应的 use)
        for (int i = 0; i < 20; i++) {
            messages.add(assistantToolUse("tu_" + i));
            messages.add(userToolResult("tu_" + i, longContent(500)));
        }

        boolean changed = pipeline.apply(messages);

        assertTrue(changed);

        for (int i = 0; i < 20; i++) {
            assertTrue(Files.exists(tempDir.resolve("tu_" + i + ".txt")),
                    "L3 应落盘 tu_" + i);
        }

        // total=2+40=42, max=10, headKeep=2 → tailStart=34. boundary 配对完整 → 不动.
        // result = head 2 + placeholder + tail 8 = 11
        assertTrue(messages.size() <= 12,
                "L1 应裁到 ≤ 12 条(maxMessages=10 + 边界保护),实际=" + messages.size());

        boolean sawSnippedPlaceholder = messages.stream()
                .anyMatch(m -> m.getContent() instanceof String s && s.startsWith("[snipped "));
        assertTrue(sawSnippedPlaceholder, "L1 应留下 [snipped ...] 占位");

        for (MessageParam m : messages) {
            if (m.getContent() instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof ToolResultBlock trb) {
                        String s = String.valueOf(trb.getContent());
                        assertTrue(s.startsWith(BudgetCompactor.STUB_PREFIX),
                                "保留下来的 tool_result 应该是 L3 stub,实际: " + s);
                    }
                }
            }
        }
    }
}
