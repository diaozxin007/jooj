package com.xilidou.marvis.agent;

import com.xilidou.marvis.MarvisTestConfig;
import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.tool.ToolRegistry;
import com.xilidou.marvis.tool.ToolDefinition;
import com.xilidou.marvis.tool.ToolResult;
import com.xilidou.marvis.hook.HookManager;
import com.xilidou.marvis.http.MockAnthropicClient;
import com.xilidou.marvis.http.ResponseFixtures;
import com.xilidou.marvis.http.dto.ContentBlock;
import com.xilidou.marvis.http.dto.CreateMessageRequest;
import com.xilidou.marvis.http.dto.CreateMessageResponse;
import com.xilidou.marvis.http.dto.InputSchema;
import com.xilidou.marvis.http.dto.MessageParam;
import com.xilidou.marvis.http.dto.TextBlock;
import com.xilidou.marvis.http.dto.ThinkingBlock;
import com.xilidou.marvis.http.dto.ToolResultBlock;
import com.xilidou.marvis.http.dto.ToolUseBlock;
import com.xilidou.marvis.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link AgentLoopHarness} 的核心行为。
 *
 * <p>切片 C 后:本测试通过 {@link SpringBootTest} 让 Spring 测试框架接管装配,
 * 配合 {@link MarvisTestConfig} 提供的 {@link MockAnthropicClient} 替身。
 * 不再 {@code new AgentLoopHarness(...)},架构上保留生产代码的"单一构造器"洁净。
 *
 * <p>每个测试 {@code @BeforeEach} 重置 mock + clear harness state,得到干净起点。
 *
 * <p>{@link SpyToolTestConfig} 注册一个 spy tool 到容器,所有测试共享 ——
 * 测试用例通过 {@code spyTool.executionCount()} / {@code spyTool.lastCall()} 断言。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MarvisTestConfig.class, AgentLoopHarnessTest.SpyToolTestConfig.class})
class AgentLoopHarnessTest {

    @Autowired AgentLoopHarness harness;
    @Autowired MockAnthropicClient mock;
    @Autowired ToolRegistry registry;
    @Autowired SpyTestTool spyTool;
    @Autowired HookManager hookManager;

    @BeforeEach
    void setUp() {
        spyTool.reset();
        harness.clearHistory();
    }

    @AfterEach
    void tearDown() {
        // 把 fixture 清回到默认抛异常状态,避免上一个测试的 fixture 串到下一个
        mock.reset(req -> {
            throw new IllegalStateException("test forgot to call mock.reset(...)");
        });
    }

    // ────────────────────────────────────────────────────────────
    //  测试 1:end_turn 立即退出
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loop should stop immediately when stop_reason is end_turn")
    void loop_should_stop_when_end_turn() {
        mock.reset(ResponseFixtures.endTurn("Hello"));

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("Say hello"));
        harness.agentLoop(messages);

        assertEquals(1, mock.getCallCount(), "应该只调用一次 LLM");
        assertEquals(0, spyTool.executionCount(), "end_turn 不应该执行任何工具");

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("assistant", messages.get(1).getRole());
    }

    // ────────────────────────────────────────────────────────────
    //  测试 2:tool_use 派发 → 执行 → 续 loop
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loop should execute tool, feed result back, and continue until end_turn")
    void loop_should_execute_tool_and_continue() {
        mock.reset(
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "value1"), "tu_001"),
                ResponseFixtures.endTurn("Done")
        );

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("Run the tool"));

        harness.agentLoop(messages);

        assertEquals(2, mock.getCallCount(), "应该调用 2 次 LLM(tool_use + end_turn)");
        assertEquals(1, spyTool.executionCount(), "test_tool 应该执行 1 次");

        ToolCall lastCall = spyTool.lastCall();
        assertEquals("test_tool", lastCall.getToolName());
        assertEquals("value1", lastCall.getArguments().get("arg"));

        assertEquals(4, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("user", messages.get(2).getRole());
        assertEquals("assistant", messages.get(3).getRole());

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
    //  测试 3:一轮多个 tool_use 全部执行
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loop should execute all tool_uses in one assistant turn")
    void loop_should_handle_multiple_tool_uses_in_one_turn() {
        ToolUseBlock tu1 = ResponseFixtures.makeToolUse("test_tool", Map.of("arg", "a"), "tu_001");
        ToolUseBlock tu2 = ResponseFixtures.makeToolUse("test_tool", Map.of("arg", "b"), "tu_002");
        ToolUseBlock tu3 = ResponseFixtures.makeToolUse("test_tool", Map.of("arg", "c"), "tu_003");

        mock.reset(
                ResponseFixtures.multipleToolUse(List.of(tu1, tu2, tu3)),
                ResponseFixtures.endTurn("All done")
        );

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("Run all tools"));

        harness.agentLoop(messages);

        assertEquals(3, spyTool.executionCount(), "3 个 tool_use 都应该被执行");

        CreateMessageRequest secondReq = mock.getRequests().get(1);
        @SuppressWarnings("unchecked")
        List<ContentBlock> toolResults = (List<ContentBlock>) secondReq.getMessages().get(2).getContent();
        assertEquals(3, toolResults.size(), "应该有 3 个 tool_result");

        assertEquals("tu_001", ((ToolResultBlock) toolResults.get(0)).getToolUseId());
        assertEquals("tu_002", ((ToolResultBlock) toolResults.get(1)).getToolUseId());
        assertEquals("tu_003", ((ToolResultBlock) toolResults.get(2)).getToolUseId());
    }

    // ────────────────────────────────────────────────────────────
    //  测试 4:assistant content 完整原样回传(坑 4)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loop should preserve full assistant content (text + thinking + tool_use) verbatim")
    void loop_should_preserve_assistant_content_verbatim() {
        CreateMessageResponse firstResp = ResponseFixtures.thinkingPlusToolUse(
                "Let me analyze...",
                "test_signature_xyz",
                "test_tool",
                Map.of("arg", "value"),
                "tu_001"
        );

        mock.reset(firstResp, ResponseFixtures.endTurn("Done"));

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("Use the tool"));

        harness.agentLoop(messages);

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
                "signature 必须原样保留(这是真实 502 的根因)");

        assertInstanceOf(ToolUseBlock.class, blocks.get(1), "第二个必须是 tool_use");
    }

    // ────────────────────────────────────────────────────────────
    //  测试 5:未知工具错误处理(loop 不崩)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loop should not crash when tool is unknown - should feed error back to LLM")
    void loop_should_return_error_for_unknown_tool() {
        mock.reset(
                ResponseFixtures.toolUse("unknown_tool", Map.of(), "tu_001"),
                ResponseFixtures.endTurn("OK, I tried something else")
        );

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("Use a tool"));

        assertDoesNotThrow(() -> harness.agentLoop(messages));

        assertEquals(0, spyTool.executionCount());
        assertEquals(2, mock.getCallCount(), "loop 应该完整跑完 2 轮");

        CreateMessageRequest secondReq = mock.getRequests().get(1);
        @SuppressWarnings("unchecked")
        List<ContentBlock> toolResults = (List<ContentBlock>) secondReq.getMessages().get(2).getContent();
        ToolResultBlock errorResult = (ToolResultBlock) toolResults.get(0);
        assertEquals("tu_001", errorResult.getToolUseId());
        String content = errorResult.getContent().toString();
        assertTrue(content.contains("unknown_tool") || content.contains("not found"),
                "错误信息应该提示工具不存在,实际:" + content);
    }

    // ────────────────────────────────────────────────────────────
    //  测试 8:s05 nag — 连续 3 轮没调 todo_write 就注入 reminder
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loop should inject reminder after NAG_THRESHOLD rounds without todo_write")
    void loop_should_inject_nag_reminder_after_3_rounds_without_todo() {
        mock.reset(
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "1"), "tu_001"),
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "2"), "tu_002"),
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "3"), "tu_003"),
                ResponseFixtures.endTurn("done")
        );

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("do work"));

        harness.agentLoop(messages);

        CreateMessageRequest fourthReq = mock.getRequests().get(3);
        boolean hasReminder = fourthReq.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .anyMatch(m -> {
                    Object c = m.getContent();
                    if (c instanceof String s) {
                        return s.contains("<reminder>");
                    }
                    if (c instanceof List<?> blocks) {
                        return blocks.stream()
                                .filter(b -> b instanceof TextBlock)
                                .map(b -> ((TextBlock) b).getText())
                                .anyMatch(t -> t.contains("<reminder>"));
                    }
                    return false;
                });
        assertTrue(hasReminder,
                "第 4 轮请求应该包含 nag reminder(连续 3 轮没调 todo_write)");
    }

    @Test
    @DisplayName("loop should NOT inject nag reminder when todo_write is called within threshold")
    void loop_should_not_nag_when_todo_write_called() {
        mock.reset(
                ResponseFixtures.toolUse("todo_write", Map.of("todos", List.of()), "tu_001"),
                ResponseFixtures.toolUse("todo_write", Map.of("todos", List.of()), "tu_002"),
                ResponseFixtures.toolUse("todo_write", Map.of("todos", List.of()), "tu_003"),
                ResponseFixtures.endTurn("done")
        );

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("plan"));

        harness.agentLoop(messages);

        for (int i = 0; i < mock.getCallCount(); i++) {
            CreateMessageRequest req = mock.getRequests().get(i);
            boolean hasReminder = req.getMessages().stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .filter(m -> m.getContent() instanceof String)
                    .anyMatch(m -> ((String) m.getContent()).contains("<reminder>"));
            assertFalse(hasReminder,
                    "第 " + (i + 1) + " 轮不该有 reminder(每轮都调了 todo_write)");
        }
    }

    // ────────────────────────────────────────────────────────────
    //  s07 review Bug 1:nag 注入不能造成 user 消息连续
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("nag 注入不能造成 user 消息连续(必须揉进上一条 user 而非新增)")
    void nag_should_not_create_consecutive_user_messages() {
        mock.reset(
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "1"), "tu_001"),
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "2"), "tu_002"),
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "3"), "tu_003"),
                ResponseFixtures.endTurn("done")
        );

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("do work"));

        harness.agentLoop(messages);

        CreateMessageRequest fourthReq = mock.getRequests().get(3);
        List<MessageParam> seq = fourthReq.getMessages();
        for (int i = 0; i < seq.size() - 1; i++) {
            String currentRole = seq.get(i).getRole();
            String nextRole = seq.get(i + 1).getRole();
            assertNotEquals(currentRole, nextRole,
                    "messages[" + i + "] 和 messages[" + (i + 1) + "] 都是 " + currentRole +
                            " — 违反 Anthropic Messages API 严格交替约束");
        }

        MessageParam lastUserBeforeAssistant = null;
        for (MessageParam m : seq) {
            if ("user".equals(m.getRole())) lastUserBeforeAssistant = m;
        }
        assertNotNull(lastUserBeforeAssistant);

        Object content = lastUserBeforeAssistant.getContent();
        assertInstanceOf(List.class, content,
                "tool_results 那条 user 的 content 应该是 List;reminder 以 TextBlock 形式追加");

        @SuppressWarnings("unchecked")
        List<ContentBlock> blocks = (List<ContentBlock>) content;
        boolean hasReminder = blocks.stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .anyMatch(t -> t.contains("<reminder>"));
        assertTrue(hasReminder,
                "reminder 文本必须以 TextBlock 形式追加在 tool_results 末尾,实际 blocks=" + blocks);
    }

    // ────────────────────────────────────────────────────────────
    //  s07 review Bug 2:processOneQuery 后 history 累积
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("processOneQuery 后 history 累积;clearHistory 可清空")
    void process_one_query_accumulates_history() {
        // 注意:processOneQuery 除主 LLM 调用,还会触发 MemoryService.onTurnEnd 调一次 extract LLM。
        // 测试有 2 次 processOneQuery → 4 次 mock 调用(2 主 + 2 extract)
        mock.reset(req -> ResponseFixtures.endTurn("answer"));

        harness.processOneQuery("question 1");
        assertEquals(2, harness.getHistory().size(),
                "query1 跑完后 history = [user1, assistant1]");

        // 模拟新会话(repl 会做这件事)
        harness.clearHistory();
        assertEquals(0, harness.getHistory().size());

        // 找到第 1 次主 LLM 调用之后的第一次调用作为 query2 的 LLM 起点
        int callsBefore = mock.getCallCount();
        harness.processOneQuery("question 2");

        // 找到 query2 的主 LLM 请求(callsBefore + extract 之后,query2 的主调用)
        // memory extract 的 user message 不是 "question 2",可以借此过滤
        boolean sawQuery2WithSingleMessage = mock.getRequests().stream()
                .skip(callsBefore)
                .anyMatch(req -> req.getMessages().size() == 1
                        && "question 2".equals(req.getMessages().get(0).getContent()));
        assertTrue(sawQuery2WithSingleMessage,
                "query2 的请求 messages 应该只有 1 条(history 已清空,且不含 query1)");
    }

    @Test
    @DisplayName("不清 history 时,query 跨调用累积")
    void without_clear_history_accumulates_across_queries() {
        mock.reset(req -> ResponseFixtures.endTurn("answer"));

        harness.processOneQuery("question 1");
        // 不调 clearHistory
        harness.processOneQuery("question 2");

        // query2 主 LLM 调用应看到 [user1, assistant1, user2] 累积历史(3 条)
        boolean sawAccumulated = mock.getRequests().stream()
                .anyMatch(req -> req.getMessages().size() == 3
                        && "user".equals(req.getMessages().get(0).getRole())
                        && "assistant".equals(req.getMessages().get(1).getRole())
                        && "user".equals(req.getMessages().get(2).getRole()));
        assertTrue(sawAccumulated,
                "未清 history 时,query2 应看到 [user1, assistant1, user2] 累积历史");
    }

    @Test
    @DisplayName("onNewSession 链式 API 返回 this,支持流畅注册")
    void onNewSession_returns_this_for_chaining() {
        AgentLoopHarness returned = harness
                .onNewSession(() -> {})
                .onNewSession(() -> {})
                .onNewSession(null);

        assertSame(harness, returned, "onNewSession 应返回 this");
    }

    @Test
    @DisplayName("onNewSession 支持注册多个回调;单个失败不影响其他")
    void onNewSession_supports_multiple_callbacks_and_fault_isolation() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        harness
                .onNewSession(fired::incrementAndGet)
                .onNewSession(() -> { throw new RuntimeException("intentional"); })
                .onNewSession(fired::incrementAndGet);

        var method = AgentLoopHarness.class.getDeclaredMethod("fireOnNewSession");
        method.setAccessible(true);
        method.invoke(harness);

        // 即使中间那个抛异常,前后两个都应该被执行
        // 注意:harness 是容器单例,@PostConstruct 已经注册过 todoStore::clear + clearHistory,
        // 所以 fired 至少 +2;另外被注册的 fired::incrementAndGet 又跑两次,合计 ≥ 2
        assertTrue(fired.get() >= 2, "中间回调抛异常不应影响其他回调执行");
    }

    // ────────────────────────────────────────────────────────────
    //  Spy Tool 注册到容器,所有测试共享
    // ────────────────────────────────────────────────────────────

    @org.springframework.boot.test.context.TestConfiguration
    static class SpyToolTestConfig {
        @org.springframework.context.annotation.Bean
        SpyTestTool spyTestTool() {
            return new SpyTestTool();
        }
    }

    /**
     * 一个简单的 Tool 实现,注册一个名为 "test_tool" 的工具。
     * 每次执行都记录调用次数和最后的 ToolCall,方便测试断言。
     * 通过 @Bean 注册到容器,被 ToolRegistry 自动收集。
     */
    static class SpyTestTool implements Tool {
        private final AtomicInteger executionCount = new AtomicInteger(0);
        private ToolCall lastCall;

        @Override public String getName() { return "test"; }

        @Override public String getDescription() { return "Test skill for unit tests"; }

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

        public int executionCount() { return executionCount.get(); }
        public ToolCall lastCall() { return lastCall; }

        public void reset() {
            executionCount.set(0);
            lastCall = null;
        }
    }
}
