package com.xilidou.marvis.harness.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xilidou.marvis.harness.agent.AgentLoopHarness;
import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.base.ToolRegistry;
import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.http.MockAnthropicClient;
import com.xilidou.marvis.harness.http.ResponseFixtures;
import com.xilidou.marvis.harness.http.dto.ContentBlock;
import com.xilidou.marvis.harness.http.dto.CreateMessageRequest;
import com.xilidou.marvis.harness.http.dto.CreateMessageResponse;
import com.xilidou.marvis.harness.http.dto.InputSchema;
import com.xilidou.marvis.harness.http.dto.MessageParam;
import com.xilidou.marvis.harness.http.dto.TextBlock;
import com.xilidou.marvis.harness.http.dto.ToolResultBlock;
import com.xilidou.marvis.harness.http.dto.ToolUseBlock;
import com.xilidou.marvis.harness.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CompactPipeline} 端到端测试 + 与 {@link AgentLoopHarness} 集成测试。
 *
 * <p>覆盖：
 * <ol>
 *   <li>L1 + L2 都触发的端到端场景</li>
 *   <li>顺序验证:snip 之后 micro 看到的 tool_result 数量已经减少</li>
 *   <li>AgentLoop 集成:80 轮 mock 响应 + 低阈值 → 验证 messages 长度受控</li>
 *   <li>AgentLoop 集成:tool_use ↔ tool_result 配对在压缩后仍合法</li>
 *   <li>压缩前后 messages 状态对比</li>
 * </ol>
 */
class CompactPipelineTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private ToolRegistry registry;
    private SpyTestTool spyTool;

    @BeforeEach
    void setUp() {
        spyTool = new SpyTestTool();
        registry = new ToolRegistry();
        registry.load(spyTool);
    }

    private static MessageParam userText(String text) {
        return MessageParam.user(text);
    }

    private static MessageParam assistantText(String text) {
        return new MessageParam("assistant", List.of(new TextBlock(text)));
    }

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
    //  测试 1：L1 + L2 同时触发
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pipeline should trigger both L1 snip and L2 micro on long history")
    void pipeline_should_apply_both_layers() {
        CompactPipeline pipeline = new CompactPipeline(new CompactConfig(8, 2, 2, 50));
        List<MessageParam> messages = new ArrayList<>();
        // 头 2 个原始消息
        messages.add(userText("query"));
        messages.add(assistantText("ok"));
        // 中间 + 尾部:塞 12 条 tool_result 长内容(总 14 条)
        for (int i = 0; i < 12; i++) {
            messages.add(userToolResult("tu_" + i, longContent(200)));
        }

        boolean changed = pipeline.apply(messages);

        assertTrue(changed, "L1 或 L2 至少有一层应该触发");
        // L1 之后 = head 2 + 占位 + 尾(14-(14-(8-2)))=8 → 总 9
        assertEquals(9, messages.size(), "L1 snip 后总 9 条");

        // 占位消息在 idx 2
        assertEquals("user", messages.get(2).getRole());
        assertEquals("[snipped 6 messages]", messages.get(2).getContent());

        // L2:剩下 6 个 tool_result(尾部),keepRecent=2 → 前 4 个被替换
        // 注意:头部 2 条无 tool_result,所以 L2 看到的只有尾部 6 个
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
        assertEquals(4, placeholderCount, "L2 应替换 6-2=4 个 tool_result");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 2：顺序验证 — snip 先于 micro
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("snip should run before micro: micro sees fewer tool_results after snip")
    void pipeline_order_snip_before_micro() {
        // 故意让头部全是 tool_result(短的),L1 不裁(因为要保护边界)的话,L2 看到的是全部
        // 否则 L1 切掉一半,L2 看到的只有尾部一半
        CompactPipeline pipeline = new CompactPipeline(new CompactConfig(6, 1, 2, 50));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("query"));  // 头 1
        // 8 条独立的(user-tool_result)消息——每条都是普通 user-tool_result,
        // 没有 tool_use 配对,故意制造"碎片化"环境
        // 但实际上 tool_result 必须配 tool_use,所以我们用 assistant_text + user_toolresult 模拟:
        // 不,Anthropic 协议要求 tool_result.tool_use_id 匹配真实 tool_use_id,
        // 这里测试逻辑层面不验证协议合法性,只看 collect 的统计,够用了。
        for (int i = 0; i < 8; i++) {
            messages.add(userToolResult("tu_" + i, longContent(150)));
        }
        // 总 9 条,maxMessages=6 → 触发 L1
        // headEnd=1, tailStart=9-(6-1)=4, snipped=3
        // L1 后:head 1 + 占位 1 + 尾 5 = 7
        // L2:剩下的 5 个 tool_result, keepRecent=2 → 前 3 个替换

        pipeline.apply(messages);

        // L1 snip 后总 7
        assertEquals(7, messages.size());
        // 占位在 idx 1
        assertEquals("[snipped 3 messages]", messages.get(1).getContent());
        // L2 看到 5 个 tool_result(idx 2..6),前 3 个(idx 2,3,4)被替换
        int placeholderCount = 0;
        for (int i = 2; i < messages.size(); i++) {
            ToolResultBlock trb = (ToolResultBlock) ((List<?>) messages.get(i).getContent()).get(0);
            if (MicroCompactor.PLACEHOLDER.equals(trb.getContent())) {
                placeholderCount++;
            }
        }
        assertEquals(3, placeholderCount, "L2 在 snip 之后看到 5 个 tool_result, 替换 3 个");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 3：AgentLoop 集成 — 长会话压缩生效,messages 长度受控
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AgentLoop integration: 30+ rounds with low maxMessages keeps last request bounded")
    void agentLoop_integration_should_keep_last_request_bounded() {
        // 构造 30 轮 tool_use 响应 + 1 轮 end_turn = 31 个响应
        // 每轮:assistant(tool_use) + user(tool_result),累积每轮 messages 增加 2 条
        // 第 31 轮发请求时 messages 应该被压到 maxMessages 附近
        List<CreateMessageResponse> responses = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            responses.add(ResponseFixtures.toolUse(
                    "test_tool",
                    Map.of("arg", "round-" + i, "padding", longContent(150)),
                    "tu_" + i));
        }
        responses.add(ResponseFixtures.endTurn("done"));

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                responses.toArray(new CreateMessageResponse[0]));

        // CompactPipeline 注入 8 参构造器
        CompactPipeline pipeline = new CompactPipeline(new CompactConfig(10, 2, 2, 50));
        AgentLoopHarness harness = new AgentLoopHarness(
                mock, "test-model", registry,
                com.xilidou.marvis.harness.JacksonConfig.newMapper(),
                com.xilidou.marvis.harness.permission.PermissionPipeline.alwaysAllow(),
                new com.xilidou.marvis.harness.hook.HookManager(),
                null,
                pipeline);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("start"));

        harness.agentLoop(messages);

        // 最后一次 LLM 请求的 messages 长度应该被压缩到接近 maxMessages
        // 注意:由于 tool_use ↔ tool_result 配对边界保护(adjustHeadEnd/adjustTailStart),
        // 实际长度可能比 maxMessages 略大(占位 +1, 边界保护可能让 head 多保留 tool_result,
        // 让 tail 多保留 tool_use)。我们用宽松上界 maxMessages + 5 验证压缩生效。
        // 关键是远小于 30 轮累积的 ~62 条。
        CreateMessageRequest lastReq = mock.getRequests().get(mock.getCallCount() - 1);
        int lastSize = lastReq.getMessages().size();
        assertTrue(lastSize <= 15,
                "L1 maxMessages=10 + boundary drift,实际应 ≤ 15,实际=" + lastSize);
        assertTrue(lastSize < 30,
                "压缩后必须远小于 30 轮累积的 messages 数(否则没生效),实际=" + lastSize);

        // 中间至少出现过一次"[snipped..."占位 message
        boolean sawPlaceholder = false;
        for (CreateMessageRequest req : mock.getRequests()) {
            for (MessageParam m : req.getMessages()) {
                Object c = m.getContent();
                if (c instanceof String s && s.startsWith("[snipped ")) {
                    sawPlaceholder = true;
                    break;
                }
            }
            if (sawPlaceholder) break;
        }
        assertTrue(sawPlaceholder, "中间至少应有一次 LLM 请求看到 [snipped...] 占位");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 4:AgentLoop 集成 — tool_use ↔ tool_result 配对在压缩后仍合法
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AgentLoop integration: compaction preserves tool_use ↔ tool_result pairing")
    void agentLoop_integration_should_preserve_tool_pair_after_compact() {
        // 构造 20 轮 tool_use 响应 + 1 轮 end_turn = 21 个响应
        List<CreateMessageResponse> responses = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            responses.add(ResponseFixtures.toolUse(
                    "test_tool", Map.of("arg", "x", "pad", longContent(200)), "tu_" + i));
        }
        responses.add(ResponseFixtures.endTurn("done"));

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                responses.toArray(new CreateMessageResponse[0]));

        CompactPipeline pipeline = new CompactPipeline(new CompactConfig(8, 2, 2, 50));
        AgentLoopHarness harness = new AgentLoopHarness(
                mock, "test-model", registry,
                com.xilidou.marvis.harness.JacksonConfig.newMapper(),
                com.xilidou.marvis.harness.permission.PermissionPipeline.alwaysAllow(),
                new com.xilidou.marvis.harness.hook.HookManager(),
                null,
                pipeline);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("start"));

        harness.agentLoop(messages);

        // 验证:每次 LLM 请求的 messages 序列里,每个 assistant.tool_use 必须紧跟 user.tool_result
        for (int reqIdx = 0; reqIdx < mock.getCallCount(); reqIdx++) {
            CreateMessageRequest req = mock.getRequests().get(reqIdx);
            List<MessageParam> seq = req.getMessages();
            for (int i = 0; i < seq.size(); i++) {
                if (MessageBoundary.hasToolUse(seq.get(i))) {
                    // 紧跟着的下一条必须是 user(tool_result)
                    assertTrue(i + 1 < seq.size(),
                            "请求 " + reqIdx + " 的 messages[" + i +
                                    "] 是 tool_use,但后面没有消息,会触发 400");
                    assertTrue(MessageBoundary.isToolResult(seq.get(i + 1)),
                            "请求 " + reqIdx + " 的 messages[" + i +
                                    "] tool_use 后面没有跟 tool_result,会触发 400");
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 5：null pipeline 跳过压缩(向后兼容)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AgentLoop with null compactPipeline: messages NOT compacted")
    void agentLoop_with_null_pipeline_skips_compaction() {
        List<CreateMessageResponse> responses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            responses.add(ResponseFixtures.toolUse("test_tool", Map.of("arg", "x"), "tu_" + i));
        }
        responses.add(ResponseFixtures.endTurn("done"));

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                responses.toArray(new CreateMessageResponse[0]));

        // 传 null pipeline
        AgentLoopHarness harness = new AgentLoopHarness(
                mock, "test-model", registry,
                com.xilidou.marvis.harness.JacksonConfig.newMapper(),
                com.xilidou.marvis.harness.permission.PermissionPipeline.alwaysAllow(),
                new com.xilidou.marvis.harness.hook.HookManager(),
                null,
                null);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("start"));
        harness.agentLoop(messages);

        // 没有压缩 = messages 一直增长 = 不应该出现 [snipped...] 占位
        for (CreateMessageRequest req : mock.getRequests()) {
            for (MessageParam m : req.getMessages()) {
                Object c = m.getContent();
                if (c instanceof String s) {
                    assertFalse(s.startsWith("[snipped "),
                            "null pipeline 不应该有 [snipped...] 占位,实际看到: " + s);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 6:L3 (budget) 与 L1/L2 串联 — 大 tool_result 落盘后 L1/L2 看到的是 stub
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pipeline order: L3 persists large tool_results before L1/L2 see them")
    void pipeline_L3_runs_before_L1_L2(@TempDir Path tempDir) throws Exception {
        // 配置:L3 阈值低(100 字节),L1/L2 也低 → 三层都会触发
        CompactConfig config = new CompactConfig(8, 2, 2, 50, 100, tempDir);
        CompactPipeline pipeline = new CompactPipeline(config);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("query"));
        messages.add(assistantText("ok"));
        // 6 条大 tool_result,每条 500 字符
        for (int i = 0; i < 6; i++) {
            messages.add(userToolResult("tu_" + i, longContent(500)));
        }
        // total = 8 条

        boolean changed = pipeline.apply(messages);

        assertTrue(changed);

        // L3 验证:全部 6 个大 tool_result 都被落盘了
        // (注意:即使后续 L1 删了一些消息,文件已经在磁盘上,不会被删)
        for (int i = 0; i < 6; i++) {
            assertTrue(Files.exists(tempDir.resolve("tu_" + i + ".txt")),
                    "L3 应在 L1 删之前先落盘 tu_" + i);
        }

        // L1 不一定真的裁(总 8 == maxMessages 边界)。下面看 L2 是否在 L3 后面"看见 stub":
        // 如果 L3 先跑,所有大 tool_result 已经替换成短 stub(< 120 字符 = minPlaceholderLen),
        // 那么 L2 不应该把它们再替换为 PLACEHOLDER(因为内容已经 < 120 + 含 STUB_PREFIX 双重保护)
        // 验证:任何 tool_result 都应该是 stub(以 STUB_PREFIX 开头),
        //       不应该是 PLACEHOLDER(说明 L2 错误地动了 L3 stub)
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
    //  测试 7:L3 + L1 + L2 三层叠加 — 巨型历史的最大压缩力度
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pipeline: huge history triggers L3 + L1 + L2 simultaneously")
    void pipeline_three_layers_combined(@TempDir Path tempDir) {
        // L3 = 200 (大点的内容才落盘); L1 maxMessages = 10; L2 keepRecent = 2
        CompactConfig config = new CompactConfig(10, 2, 2, 50, 200, tempDir);
        CompactPipeline pipeline = new CompactPipeline(config);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("query"));
        messages.add(assistantText("ok"));
        // 20 条大 tool_result(每条 500 字符)
        for (int i = 0; i < 20; i++) {
            messages.add(userToolResult("tu_" + i, longContent(500)));
        }
        // total = 22 条

        boolean changed = pipeline.apply(messages);

        assertTrue(changed);

        // L3:落盘 20 个文件
        for (int i = 0; i < 20; i++) {
            assertTrue(Files.exists(tempDir.resolve("tu_" + i + ".txt")),
                    "L3 应落盘 tu_" + i);
        }

        // L1:总条数 22 → 10 附近(头 2 + 占位 + 尾 7)= 10 条
        assertTrue(messages.size() <= 12,
                "L1 应裁到 ≤ 12 条(maxMessages=10 + 边界保护),实际=" + messages.size());

        // 出现过 [snipped 占位 message
        boolean sawSnippedPlaceholder = messages.stream()
                .anyMatch(m -> m.getContent() instanceof String s && s.startsWith("[snipped "));
        assertTrue(sawSnippedPlaceholder, "L1 应留下 [snipped ...] 占位");

        // L2:剩下的 tool_result 都是 L3 stub,不应被 L2 PLACEHOLDER 误替换
        // (再次验证 L2 vs L3 边界)
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

    // ────────────────────────────────────────────────────────────
    //  测试用 Spy Tool
    // ────────────────────────────────────────────────────────────

    private static class SpyTestTool implements Tool {
        private final AtomicInteger executionCount = new AtomicInteger(0);
        private ToolCall lastCall;

        @Override
        public String getName() { return "test"; }

        @Override
        public String getDescription() { return "Test skill for compact tests"; }

        @Override
        public List<ToolDefinition> getTools() {
            return List.of(new ToolDefinition(
                    "test_tool",
                    "A test tool that returns ok with padding",
                    InputSchema.object(
                            Map.of("arg", Map.of("type", "string", "description", "arg")),
                            "arg"
                    )
            ));
        }

        @Override
        public ToolResult execute(ToolCall call) {
            executionCount.incrementAndGet();
            lastCall = call;
            // 返回长内容确保 micro 替换条件满足
            return new ToolResult(true, "ok " + longContent(200));
        }

        @SuppressWarnings("unused")
        public int getExecutionCount() { return executionCount.get(); }

        @SuppressWarnings("unused")
        public ToolCall getLastCall() { return lastCall; }
    }
}
