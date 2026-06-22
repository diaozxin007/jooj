package com.xilidou.marvis.harness.agent;

import com.xilidou.marvis.harness.base.SkillRegistry;
import com.xilidou.marvis.harness.base.ToolCall;
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
import com.xilidou.marvis.harness.http.dto.ThinkingBlock;
import com.xilidou.marvis.harness.http.dto.ToolResultBlock;
import com.xilidou.marvis.harness.http.dto.ToolUseBlock;
import com.xilidou.marvis.harness.skill.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link AgentLoopHarness} 的核心行为。
 *
 * <p>这些测试不依赖真实 LLM，全部通过 {@link MockAnthropicClient} 注入预设响应。
 * 5 个测试覆盖 5 个关键场景：
 *
 * <ol>
 *   <li><b>end_turn 立即退出</b>：stop_reason != tool_use 时不调工具</li>
 *   <li><b>tool_use 派发</b>：执行工具 → 把结果回传 → 继续 loop</li>
 *   <li><b>多 tool_use 并发</b>：一轮内多个 tool_use 全部执行</li>
 *   <li><b>assistant content 完整回传</b>（坑 4）：含 thinking 也要完整保留</li>
 *   <li><b>未知工具错误处理</b>：单个工具失败不让 loop 崩，错误回传给 LLM</li>
 * </ol>
 *
 * <p>跑这些测试 = **没烧 token，几百毫秒就跑完**。
 */
class AgentLoopHarnessTest {

    private SkillRegistry registry;
    private SpyTestSkill spySkill;

    @BeforeEach
    void setUp() {
        spySkill = new SpyTestSkill();
        registry = new SkillRegistry();
        registry.load(spySkill);
    }

    // ────────────────────────────────────────────────────────────
    //  测试 1：end_turn 立即退出
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loop should stop immediately when stop_reason is end_turn")
    void loop_should_stop_when_end_turn() {
        // Given：mock 返回一个 end_turn 响应
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("Hello")
        );
        AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry);

        // When：跑一次 loop
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("Say hello"));
        harness.agentLoop(messages);

        // Then：
        assertEquals(1, mock.getCallCount(), "应该只调用一次 LLM");
        assertEquals(0, spySkill.getExecutionCount(), "end_turn 不应该执行任何工具");

        // messages 应该是 [user, assistant(end_turn)]
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("assistant", messages.get(1).getRole());
    }

    // ────────────────────────────────────────────────────────────
    //  测试 2：tool_use 派发 → 执行 → 续 loop
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loop should execute tool, feed result back, and continue until end_turn")
    void loop_should_execute_tool_and_continue() {
        // Given：第 1 轮 tool_use，第 2 轮 end_turn
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "value1"), "tu_001"),
                ResponseFixtures.endTurn("Done")
        );
        AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("Run the tool"));

        // When
        harness.agentLoop(messages);

        // Then：LLM 调了 2 次，工具执行了 1 次
        assertEquals(2, mock.getCallCount(), "应该调用 2 次 LLM（tool_use + end_turn）");
        assertEquals(1, spySkill.getExecutionCount(), "test_tool 应该执行 1 次");

        // 验证工具收到的参数正确（input.arg = "value1"）
        ToolCall lastCall = spySkill.getLastCall();
        assertEquals("test_tool", lastCall.getToolName());
        assertEquals("value1", lastCall.getArguments().get("arg"));

        // messages 应该是 [user, assistant(tool_use), user(tool_result), assistant(end_turn)]
        assertEquals(4, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("user", messages.get(2).getRole());        // tool_result 包在 user 里（坑 3）
        assertEquals("assistant", messages.get(3).getRole());

        // 验证第二轮请求里包含了 tool_result
        CreateMessageRequest secondRequest = mock.getRequests().get(1);
        assertEquals(3, secondRequest.getMessages().size());
        Object content = secondRequest.getMessages().get(2).getContent();
        assertInstanceOf(List.class, content, "tool_result 应该是 List<ContentBlock>");
        @SuppressWarnings("unchecked")
        List<ContentBlock> blocks = (List<ContentBlock>) content;
        assertInstanceOf(ToolResultBlock.class, blocks.get(0));
        ToolResultBlock tr = (ToolResultBlock) blocks.get(0);
        assertEquals("tu_001", tr.getToolUseId(), "tool_use_id 必须匹配");
    }

    // ────────────────────────────────────────────────────────────
    //  测试 3：一轮多个 tool_use 全部执行
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loop should execute all tool_uses in one assistant turn")
    void loop_should_handle_multiple_tool_uses_in_one_turn() {
        // Given：一轮包含 3 个 tool_use
        ToolUseBlock tu1 = ResponseFixtures.makeToolUse("test_tool", Map.of("arg", "a"), "tu_001");
        ToolUseBlock tu2 = ResponseFixtures.makeToolUse("test_tool", Map.of("arg", "b"), "tu_002");
        ToolUseBlock tu3 = ResponseFixtures.makeToolUse("test_tool", Map.of("arg", "c"), "tu_003");

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.multipleToolUse(List.of(tu1, tu2, tu3)),
                ResponseFixtures.endTurn("All done")
        );
        AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("Run all tools"));

        // When
        harness.agentLoop(messages);

        // Then：3 个工具都被执行
        assertEquals(3, spySkill.getExecutionCount(), "3 个 tool_use 都应该被执行");

        // 第二轮请求里应该包含 3 个 tool_result
        CreateMessageRequest secondReq = mock.getRequests().get(1);
        @SuppressWarnings("unchecked")
        List<ContentBlock> toolResults = (List<ContentBlock>) secondReq.getMessages().get(2).getContent();
        assertEquals(3, toolResults.size(), "应该有 3 个 tool_result");

        // tool_use_id 必须分别对应
        assertEquals("tu_001", ((ToolResultBlock) toolResults.get(0)).getToolUseId());
        assertEquals("tu_002", ((ToolResultBlock) toolResults.get(1)).getToolUseId());
        assertEquals("tu_003", ((ToolResultBlock) toolResults.get(2)).getToolUseId());
    }

    // ────────────────────────────────────────────────────────────
    //  测试 4：assistant content 完整原样回传（坑 4）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loop should preserve full assistant content (text + thinking + tool_use) verbatim")
    void loop_should_preserve_assistant_content_verbatim() {
        // Given：模型返回 thinking + tool_use（Claude Sonnet 4.6 真实场景）
        CreateMessageResponse firstResp = ResponseFixtures.thinkingPlusToolUse(
                "Let me analyze...",
                "test_signature_xyz",
                "test_tool",
                Map.of("arg", "value"),
                "tu_001"
        );

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                firstResp,
                ResponseFixtures.endTurn("Done")
        );
        AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("Use the tool"));

        // When
        harness.agentLoop(messages);

        // Then：第二轮请求里 assistant 消息必须包含 thinking + tool_use 两个 block
        CreateMessageRequest secondReq = mock.getRequests().get(1);
        MessageParam assistantMsg = secondReq.getMessages().get(1);
        assertEquals("assistant", assistantMsg.getRole());

        @SuppressWarnings("unchecked")
        List<ContentBlock> blocks = (List<ContentBlock>) assistantMsg.getContent();
        assertEquals(2, blocks.size(), "thinking + tool_use 两个 block 都必须保留");

        assertInstanceOf(ThinkingBlock.class, blocks.get(0), "第一个必须是 thinking");
        ThinkingBlock thinking = (ThinkingBlock) blocks.get(0);
        assertEquals("Let me analyze...", thinking.getThinking());
        assertEquals("test_signature_xyz", thinking.getSignature(),
                "signature 必须原样保留（这是真实 502 的根因）");

        assertInstanceOf(ToolUseBlock.class, blocks.get(1), "第二个必须是 tool_use");
    }

    // ────────────────────────────────────────────────────────────
    //  测试 5：未知工具错误处理（loop 不崩）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loop should not crash when tool is unknown - should feed error back to LLM")
    void loop_should_return_error_for_unknown_tool() {
        // Given：模型调一个未注册的工具
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.toolUse("unknown_tool", Map.of(), "tu_001"),
                ResponseFixtures.endTurn("OK, I tried something else")
        );
        AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("Use a tool"));

        // When：loop 应该正常完成，不抛异常
        assertDoesNotThrow(() -> harness.agentLoop(messages));

        // Then：unknown_tool 没被执行，但 loop 完成了
        assertEquals(0, spySkill.getExecutionCount());
        assertEquals(2, mock.getCallCount(), "loop 应该完整跑完 2 轮");

        // 第二轮请求里应该有一个 tool_result，内容是错误信息
        CreateMessageRequest secondReq = mock.getRequests().get(1);
        @SuppressWarnings("unchecked")
        List<ContentBlock> toolResults = (List<ContentBlock>) secondReq.getMessages().get(2).getContent();
        ToolResultBlock errorResult = (ToolResultBlock) toolResults.get(0);
        assertEquals("tu_001", errorResult.getToolUseId());
        // 错误内容应该提到工具名（让 LLM 知道是哪个工具失败了）
        String content = errorResult.getContent().toString();
        assertTrue(content.contains("unknown_tool") || content.contains("not found"),
                "错误信息应该提示工具不存在，实际：" + content);
    }

    // ────────────────────────────────────────────────────────────
    //  测试用 Spy Skill：记录调用次数和参数
    // ────────────────────────────────────────────────────────────

    /**
     * 一个简单的 Skill 实现，注册一个名为 "test_tool" 的工具。
     * 每次执行都记录调用次数和最后的 ToolCall，方便测试断言。
     */
    private static class SpyTestSkill implements Skill {
        private final AtomicInteger executionCount = new AtomicInteger(0);
        private ToolCall lastCall;

        @Override
        public String getName() { return "test"; }

        @Override
        public String getDescription() { return "Test skill for unit tests"; }

        @Override
        public List<ToolDefinition> getTools() {
            return List.of(new ToolDefinition(
                    "test_tool",
                    "A test tool that always returns 'ok'",
                    InputSchema.object(
                            Map.of("arg", Map.of("type", "string", "description", "Test argument")),
                            "arg"
                    )
            ));
        }

        @Override
        public ToolResult execute(ToolCall call) {
            executionCount.incrementAndGet();
            lastCall = call;
            return new ToolResult(true, "ok: " + call.getArguments());
        }

        public int getExecutionCount() { return executionCount.get(); }
        public ToolCall getLastCall() { return lastCall; }
    }
}
