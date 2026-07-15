package com.xilidou.jooj.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRole;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import com.xilidou.jooj.llm.domain.LlmUsage;
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
 * <p>P2 Step G:fixture 迁到 canonical {@link LlmMessage} 及其 sealed content。
 * "user 里放 tool_result" 的老 wire 语义换成 TOOL 一等 role。usage 换成 {@link LlmUsage}。
 */
class CompactPipelineTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private static LlmMessage userText(String text) {
        return LlmMessage.userText(text);
    }

    private static LlmMessage assistantText(String text) {
        return LlmMessage.assistant(List.of(new LlmText(text)));
    }

    @SuppressWarnings("unused")
    private static LlmMessage assistantToolUse(String id) {
        JsonNode input = JSON.objectNode();
        return LlmMessage.assistant(List.of(new LlmToolCall(id, "test_tool", input)));
    }

    private static LlmMessage userToolResult(String id, String content) {
        return LlmMessage.toolResults(new ArrayList<>(List.of(LlmToolResult.success(id, content))));
    }

    private static String longContent(int len) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < len) sb.append("xxxx ");
        return sb.toString();
    }

    /** 首个 LlmText 的 text —— placeholder 消息用来锁定字面前缀。 */
    private static String firstTextOf(LlmMessage m) {
        for (LlmContent c : m.getContent()) {
            if (c instanceof LlmText t) return t.getText();
        }
        return null;
    }

    /** canonical Usage builder,方便老 4 参 wire ctor 就地迁移。 */
    private static LlmUsage usage(int in, int out, Integer cacheCreate, Integer cacheRead) {
        return LlmUsage.builder()
                .inputTokens(in)
                .outputTokens(out)
                .cacheCreationInputTokens(cacheCreate)
                .cacheReadInputTokens(cacheRead)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 1:L1 + L2 同时触发
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pipeline should trigger both L1 snip and L2 micro on long history")
    void pipeline_should_apply_both_layers() {
        CompactPipeline pipeline = new CompactPipeline(new CompactConfig(8, 2, 2, 50));
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(userText("query"));
        messages.add(assistantText("ok"));
        // s21 Demo 25:fixture 改成成对 (tool_call, tool_result),让 boundary walk
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

        // Placeholder 消息是 LlmMessage.userText 生成的 USER role
        assertEquals(LlmRole.USER, messages.get(2).getRole());
        String placeholder = firstTextOf(messages.get(2));
        assertNotNull(placeholder);
        assertTrue(placeholder.startsWith("[snipped 18 messages"),
                "占位应以 [snipped 18 messages 开头,实际:" + placeholder);

        // tail 6 条 = 3 对 (tu_9_call, tu_9_result, tu_10_call, tu_10_result, tu_11_call, tu_11_result)
        // 含 3 个 tool_result, keepRecent=2 → L2 替换 1 个
        int placeholderCount = 0;
        for (LlmMessage m : messages) {
            for (LlmContent c : m.getContent()) {
                if (c instanceof LlmToolResult tr &&
                        MicroCompactor.PLACEHOLDER.equals(tr.getOutput())) {
                    placeholderCount++;
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
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(userText("query"));
        // s21 Demo 25:成对 fixture
        for (int i = 0; i < 8; i++) {
            messages.add(assistantToolUse("tu_" + i));
            messages.add(userToolResult("tu_" + i, longContent(150)));
        }

        pipeline.apply(messages);

        // total=1+16=17, max=6, headKeep=1 → tailStart=12.
        // tail [12..17)=5 条 (tu_5_result, tu_6_call, tu_6_result, tu_7_call, tu_7_result),
        // openResults={5,6,7},tu_call 6,7 配对,tu_5 unmatched → 退到 11.
        // tail [11..17) 6 条 = 3 对配对 → 停。snipped=11-1=10
        // result = head 1 + placeholder + tail 6 = 8
        assertEquals(8, messages.size());
        String ph = firstTextOf(messages.get(1));
        assertNotNull(ph);
        assertTrue(ph.startsWith("[snipped 10 messages"),
                "占位应以 [snipped 10 messages 开头,实际:" + ph);

        // tail 6 条 = 3 对 → 3 个 tool_result, keepRecent=2 → replace 1
        int placeholderCount = 0;
        for (int i = 2; i < messages.size(); i++) {
            LlmMessage m = messages.get(i);
            if (m.getContent() != null && !m.getContent().isEmpty()
                    && m.getContent().get(0) instanceof LlmToolResult tr
                    && MicroCompactor.PLACEHOLDER.equals(tr.getOutput())) {
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

        List<LlmMessage> messages = new ArrayList<>();
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

        for (LlmMessage m : messages) {
            for (LlmContent c : m.getContent()) {
                if (c instanceof LlmToolResult tr) {
                    String s = tr.getOutput() != null ? tr.getOutput() : "";
                    assertNotEquals(MicroCompactor.PLACEHOLDER, s,
                            "L3 stub 不应被 L2 替换为 PLACEHOLDER (前缀检查失效?)");
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

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(userText("query"));
        messages.add(assistantText("ok"));
        // s21 Demo 25:成对 fixture(每个 result 都有对应的 call)
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
                .anyMatch(m -> {
                    String t = firstTextOf(m);
                    return t != null && t.startsWith("[snipped ");
                });
        assertTrue(sawSnippedPlaceholder, "L1 应留下 [snipped ...] 占位");

        for (LlmMessage m : messages) {
            for (LlmContent c : m.getContent()) {
                if (c instanceof LlmToolResult tr) {
                    String s = tr.getOutput() != null ? tr.getOutput() : "";
                    assertTrue(s.startsWith(BudgetCompactor.STUB_PREFIX),
                            "保留下来的 tool_result 应该是 L3 stub,实际: " + s);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  s22 D:token-aware 触发门禁测试
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("token-aware 禁用(contextLength=0)时 shouldCompress 恒 false,isTokenAwareEnabled false")
    void token_aware_disabled_by_default() {
        var pipeline = new com.xilidou.jooj.compact.CompactPipeline();
        assertFalse(pipeline.isTokenAwareEnabled(), "默认构造器应禁用 token-aware");
        assertFalse(pipeline.shouldCompress(), "禁用时 shouldCompress 恒 false");

        // 即使塞 usage 也不改变
        var u = usage(999_999, 0, 0, 0);
        pipeline.updateFromResponse(u);
        assertFalse(pipeline.shouldCompress(), "禁用时无视 usage");
    }

    @Test
    @DisplayName("token-aware 启用:pressure < threshold 不触发")
    void token_aware_under_threshold_no_compress() {
        var config = new CompactConfig(50, 3, 3, 120);
        // contextLength=200_000, threshold=0.7 → threshold_tokens = 140_000
        var pipeline = new com.xilidou.jooj.compact.CompactPipeline(
                config, null, null, null, 200_000, 0.70);
        assertTrue(pipeline.isTokenAwareEnabled());
        assertEquals(140_000L, pipeline.thresholdTokens());

        // 塞 10K input + 5K cache_read = 15K,远低于 140K
        pipeline.updateFromResponse(usage(10_000, 0, 0, 5_000));
        assertEquals(15_000L, pipeline.lastPromptTokens());
        assertFalse(pipeline.shouldCompress(), "15K < 140K 不该触发");
    }

    @Test
    @DisplayName("token-aware 启用:pressure ≥ threshold 触发")
    void token_aware_over_threshold_triggers_compress() {
        var config = new CompactConfig(50, 3, 3, 120);
        var pipeline = new com.xilidou.jooj.compact.CompactPipeline(
                config, null, null, null, 200_000, 0.70);

        // 塞 100K input + 45K cache_read = 145K > 140K
        pipeline.updateFromResponse(usage(100_000, 0, 0, 45_000));
        assertEquals(145_000L, pipeline.lastPromptTokens());
        assertTrue(pipeline.shouldCompress(), "145K > 140K 应触发");
    }

    @Test
    @DisplayName("updateFromResponse null-safe")
    void update_from_response_null_safe() {
        var config = new CompactConfig(50, 3, 3, 120);
        var pipeline = new com.xilidou.jooj.compact.CompactPipeline(
                config, null, null, null, 200_000, 0.70);

        // null usage 不改
        pipeline.updateFromResponse(null);
        assertEquals(0L, pipeline.lastPromptTokens());

        // 全 0 usage 不改
        pipeline.updateFromResponse(usage(0, 0, 0, 0));
        assertEquals(0L, pipeline.lastPromptTokens());

        // 有值才更新
        pipeline.updateFromResponse(usage(1000, 0, 0, 0));
        assertEquals(1000L, pipeline.lastPromptTokens());

        // 再来个 null,值保留
        pipeline.updateFromResponse(null);
        assertEquals(1000L, pipeline.lastPromptTokens(), "null 应保留旧值不重置");
    }

    @Test
    @DisplayName("thresholdPercent 边界:0 或 >1 直接抛 IAE")
    void threshold_percent_validation() {
        var config = new CompactConfig(50, 3, 3, 120);
        assertThrows(IllegalArgumentException.class,
                () -> new com.xilidou.jooj.compact.CompactPipeline(
                        config, null, null, null, 200_000, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new com.xilidou.jooj.compact.CompactPipeline(
                        config, null, null, null, 200_000, 1.5));
    }

    // ─────────────────────────────────────────────────────────────
    //  s22 D-5:compressIfNeeded 决策 + log 场景测试
    //
    //  模拟一段真实 agent turn 序列(pressure 从低到高):
    //  turn 1 (15K):门禁 skip → messages 数量 & 内容全保留
    //  turn 2 (100K):门禁 skip → 仍然保留
    //  turn 3 (145K > 140K threshold):门禁 apply → 触发 L1 snip
    //
    //  这个测试是 D 的**行为证据**:证明 gate 会把"apply" 延后到真正接近爆炸,
    //  而不是每轮 turn 都无脑削。
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("compressIfNeeded 决策序列:低压 skip → 高压 apply")
    void compress_if_needed_decision_sequence() {
        // maxMessages=8 → L1 会在 messages > 8 时触发
        var config = new CompactConfig(8, 2, 2, 50);
        var pipeline = new com.xilidou.jooj.compact.CompactPipeline(
                config, null, null, null, 200_000, 0.70);

        // 造一段长历史(至少 20 条对话),让 L1 有削减空间
        var messages = new ArrayList<LlmMessage>();
        messages.add(userText("query"));
        for (int i = 0; i < 12; i++) {
            messages.add(assistantToolUse("tu_" + i));
            messages.add(userToolResult("tu_" + i, longContent(80)));
        }
        int fullSize = messages.size();
        assertEquals(25, fullSize, "sanity: 1 + 12*2 = 25 条");

        // ── turn 1: pressure=15K,远低于 140K threshold ──
        pipeline.updateFromResponse(usage(10_000, 0, 0, 5_000));
        boolean applied1 = pipeline.compressIfNeeded(messages);
        assertFalse(applied1, "turn 1 15K < 140K,不该压");
        assertEquals(fullSize, messages.size(), "turn 1 messages 数量应保留");

        // ── turn 2: pressure=100K,还没到 140K ──
        pipeline.updateFromResponse(usage(80_000, 0, 0, 20_000));
        boolean applied2 = pipeline.compressIfNeeded(messages);
        assertFalse(applied2, "turn 2 100K < 140K,不该压");
        assertEquals(fullSize, messages.size(), "turn 2 messages 数量应保留");

        // ── turn 3: pressure=145K,超过 140K → 触发压缩 ──
        pipeline.updateFromResponse(usage(100_000, 0, 0, 45_000));
        assertEquals(145_000L, pipeline.lastPromptTokens());
        boolean applied3 = pipeline.compressIfNeeded(messages);
        assertTrue(applied3, "turn 3 145K > 140K,应该压");
        assertTrue(messages.size() < fullSize,
                "L1 应削短 messages, before=" + fullSize + ", after=" + messages.size());
    }

    @Test
    @DisplayName("compressIfNeeded 门禁关闭(contextLength=0):总是 apply,兜底旧行为")
    void compress_if_needed_gate_disabled_always_applies() {
        // token-aware 关闭 —— maxMessages=8,messages=25 会命中 L1 兜底
        var config = new CompactConfig(8, 2, 2, 50);
        var pipeline = new com.xilidou.jooj.compact.CompactPipeline(config);
        assertFalse(pipeline.isTokenAwareEnabled());

        var messages = new ArrayList<LlmMessage>();
        messages.add(userText("query"));
        for (int i = 0; i < 12; i++) {
            messages.add(assistantToolUse("tu_" + i));
            messages.add(userToolResult("tu_" + i, longContent(80)));
        }

        // 从未 updateFromResponse —— pressure=0,但门禁关闭时"总是 apply"
        boolean applied = pipeline.compressIfNeeded(messages);
        assertTrue(applied, "门禁关闭时 apply 一定会跑;messages=25 > maxMessages=8,L1 兜底应触发");
        assertTrue(messages.size() < 25, "L1 应削短");
    }

    @Test
    @DisplayName("compressIfNeeded 门禁开启 + 未过阈值:apply 完全不跑(messages 一字不动)")
    void compress_if_needed_gate_blocks_apply_when_under_threshold() {
        // 关键:即使 messages 远超 maxMessages,只要 pressure 没到阈值,门禁就应该拦住
        // —— 这是 D 相对旧行为的核心区别(旧行为会依赖 message-count 兜底立刻压掉)
        var config = new CompactConfig(8, 2, 2, 50);
        var pipeline = new com.xilidou.jooj.compact.CompactPipeline(
                config, null, null, null, 200_000, 0.70);

        var messages = new ArrayList<LlmMessage>();
        messages.add(userText("query"));
        for (int i = 0; i < 12; i++) {
            messages.add(assistantToolUse("tu_" + i));
            messages.add(userToolResult("tu_" + i, longContent(80)));
        }
        int before = messages.size();

        // pressure=5K,远低于 140K → 门禁拦住,即使 messages 已远超 maxMessages
        pipeline.updateFromResponse(usage(5_000, 0, 0, 0));
        boolean applied = pipeline.compressIfNeeded(messages);
        assertFalse(applied, "pressure 未过阈值,门禁应完全拦住");
        assertEquals(before, messages.size(),
                "messages 应一字不动(即使 count 超 maxMessages 也不该 apply)");
    }
}
