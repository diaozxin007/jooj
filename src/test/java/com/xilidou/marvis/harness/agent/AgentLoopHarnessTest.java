package com.xilidou.marvis.harness.agent;

import com.xilidou.marvis.harness.base.ToolRegistry;
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
import com.xilidou.marvis.harness.tool.Tool;
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

    private ToolRegistry registry;
    private SpyTestTool spyTool;

    @BeforeEach
    void setUp() {
        spyTool = new SpyTestTool();
        registry = new ToolRegistry();
        registry.load(spyTool);
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
        assertEquals(0, spyTool.getExecutionCount(), "end_turn 不应该执行任何工具");

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
        assertEquals(1, spyTool.getExecutionCount(), "test_tool 应该执行 1 次");

        // 验证工具收到的参数正确（input.arg = "value1"）
        ToolCall lastCall = spyTool.getLastCall();
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
        assertEquals(3, spyTool.getExecutionCount(), "3 个 tool_use 都应该被执行");

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
        assertEquals(0, spyTool.getExecutionCount());
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
    //  测试 6：PreToolUse hook 阻止时不执行工具，把原因回传 LLM
    //  （s04 重构后：Loop 通过 hooks.triggerPreToolUse 而不是直接调 permission）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loop should skip tool execution when PreToolUse hook blocks, feed reason back to LLM")
    void loop_should_skip_tool_on_pre_tool_hook_block() {
        // 自定义 PreToolUse hook：永远返回非空 Optional（= 阻止）
        com.xilidou.marvis.harness.hook.HookManager hooks = new com.xilidou.marvis.harness.hook.HookManager()
                .register((com.xilidou.marvis.harness.hook.Hook.OnPreToolUse) toolUse ->
                        java.util.Optional.of("blocked by test hook"));

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "value"), "tu_001"),
                ResponseFixtures.endTurn("OK, will try something else")
        );

        AgentLoopHarness harness = new AgentLoopHarness(
                mock, "test-model", registry,
                com.xilidou.marvis.harness.JacksonConfig.newMapper(),
                com.xilidou.marvis.harness.permission.PermissionPipeline.alwaysAllow(),
                hooks
        );

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("Run test_tool"));

        // When
        harness.agentLoop(messages);

        // Then：
        // 1. spyTool 不应被执行（hook 拦截在前）
        assertEquals(0, spyTool.getExecutionCount(),
                "PreToolUse hook 阻止时 executeOneTool 不应被调用");

        // 2. loop 继续跑完（2 轮 LLM 调用：第一轮 tool_use，第二轮 end_turn）
        //    block 不应让 loop 提前退出，应该把原因反馈回 LLM 让其继续
        assertEquals(2, mock.getCallCount(),
                "loop 应该把 hook 阻止反馈回 LLM 让其继续，不是直接返回");

        // 3. 第二轮请求里应该包含 hook 阻止原因的 tool_result
        CreateMessageRequest secondReq = mock.getRequests().get(1);
        @SuppressWarnings("unchecked")
        List<ContentBlock> toolResults = (List<ContentBlock>) secondReq.getMessages().get(2).getContent();
        ToolResultBlock denyResult = (ToolResultBlock) toolResults.get(0);
        assertEquals("tu_001", denyResult.getToolUseId(),
                "tool_use_id 必须匹配，否则 LLM 不知道是哪个工具被拒");
        String content = denyResult.getContent().toString();
        assertEquals("blocked by test hook", content,
                "tool_result 内容应该是 hook 返回的字符串原文");
    }

    // ────────────────────────────────────────────────────────────
    //  测试 7：PermissionHook 集成 — 验证 s03 → s04 行为等价
    //  PermissionPipeline 通过 PermissionHook 包装后，依然能正确拦截工具
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PermissionHook should bridge PermissionPipeline DENY into PreToolUse hook block")
    void permission_hook_should_bridge_pipeline_to_hook() {
        // 用一个永远 deny 的 pipeline
        com.xilidou.marvis.harness.permission.PermissionPipeline blockAll =
                new com.xilidou.marvis.harness.permission.PermissionPipeline(
                        List.of(toolUse -> com.xilidou.marvis.harness.permission.PermissionResult.deny("Gate 1 hit")),
                        new com.xilidou.marvis.harness.permission.UserApprovalGate(
                                com.xilidou.marvis.harness.permission.UserApprover.ALWAYS_ALLOW
                        )
                );

        // 把 pipeline 包成 PermissionHook，注册到 HookManager
        com.xilidou.marvis.harness.hook.HookManager hooks = new com.xilidou.marvis.harness.hook.HookManager()
                .register(new com.xilidou.marvis.harness.hook.impl.PermissionHook(blockAll));

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "value"), "tu_001"),
                ResponseFixtures.endTurn("OK")
        );

        AgentLoopHarness harness = new AgentLoopHarness(
                mock, "test-model", registry,
                com.xilidou.marvis.harness.JacksonConfig.newMapper(),
                blockAll,
                hooks
        );

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("Run test_tool"));

        // When
        harness.agentLoop(messages);

        // Then
        assertEquals(0, spyTool.getExecutionCount(),
                "PermissionHook 应该通过 hook 总线拦截工具");

        CreateMessageRequest secondReq = mock.getRequests().get(1);
        @SuppressWarnings("unchecked")
        List<ContentBlock> toolResults = (List<ContentBlock>) secondReq.getMessages().get(2).getContent();
        String content = ((ToolResultBlock) toolResults.get(0)).getContent().toString();
        assertTrue(content.contains("Permission denied"),
                "PermissionHook 返回的内容应该带 'Permission denied' 前缀");
        assertTrue(content.contains("Gate 1 hit"),
                "PermissionHook 应该把 Pipeline 的 reason 透传出来");
    }

    // ────────────────────────────────────────────────────────────
    //  测试 8：s05 nag — 连续 3 轮没调 todo_write 就注入 reminder
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loop should inject reminder after NAG_THRESHOLD rounds without todo_write")
    void loop_should_inject_nag_reminder_after_3_rounds_without_todo() {
        // 4 轮都调 test_tool（不是 todo_write）→ 第 4 轮前应该看到 nag reminder
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "1"), "tu_001"),  // 第 1 轮
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "2"), "tu_002"),  // 第 2 轮
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "3"), "tu_003"),  // 第 3 轮 → 触发 nag
                ResponseFixtures.endTurn("done")                                       // 第 4 轮 end
        );

        AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("do work"));

        harness.agentLoop(messages);

        // 验证：第 4 轮请求里应该有 reminder 注入
        // s07 review Bug 1 修复后：reminder 揉进上一条 user(tool_results) 里的 TextBlock，
        // 不再以独立 user 消息存在（避免 user→user 连续）。所以同时检查两种形态：
        //   - String content 含 "<reminder>"（极端兜底路径）
        //   - List<ContentBlock> 里某个 TextBlock 含 "<reminder>"（常规修复路径）
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
                "第 4 轮请求应该包含 nag reminder（连续 3 轮没调 todo_write）");
    }

    @Test
    @DisplayName("loop should NOT inject nag reminder when todo_write is called within threshold")
    void loop_should_not_nag_when_todo_write_called() {
        // 每次都调 todo_write → 永远不应该 nag
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.toolUse("todo_write", Map.of("todos", List.of()), "tu_001"),
                ResponseFixtures.toolUse("todo_write", Map.of("todos", List.of()), "tu_002"),
                ResponseFixtures.toolUse("todo_write", Map.of("todos", List.of()), "tu_003"),
                ResponseFixtures.endTurn("done")
        );

        // 注册一个 todo_write 工具占位（不必真做事，让 registry 能 dispatch）
        com.xilidou.marvis.harness.todo.TodoStore store = new com.xilidou.marvis.harness.todo.TodoStore();
        registry.load(new com.xilidou.marvis.harness.tool.impl.TodoTool(store));

        AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("plan"));

        harness.agentLoop(messages);

        // 任何一轮请求都不应该有 reminder
        for (int i = 0; i < mock.getCallCount(); i++) {
            CreateMessageRequest req = mock.getRequests().get(i);
            boolean hasReminder = req.getMessages().stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .filter(m -> m.getContent() instanceof String)
                    .anyMatch(m -> ((String) m.getContent()).contains("<reminder>"));
            assertFalse(hasReminder,
                    "第 " + (i + 1) + " 轮不该有 reminder（每轮都调了 todo_write）");
        }
    }

    // ────────────────────────────────────────────────────────────
    //  测试 9：onNewSession 回调（s05 bug 修复 — TodoStore 跨任务清理）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("onNewSession 注册的回调应在 agentLoop 调用时不被触发")
    void onNewSession_should_not_fire_during_agent_loop() {
        // agentLoop 是单任务执行，不该触发 onNewSession（那是 repl 的职责）
        java.util.concurrent.atomic.AtomicInteger callbackFiredCount =
                new java.util.concurrent.atomic.AtomicInteger();

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("hi")
        );
        AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry)
                .onNewSession(callbackFiredCount::incrementAndGet);

        harness.agentLoop(new ArrayList<>(List.of(MessageParam.user("hello"))));

        assertEquals(0, callbackFiredCount.get(),
                "agentLoop 是子任务执行入口，不该触发 onNewSession（那是 repl 的语义）");
    }

    @Test
    @DisplayName("onNewSession 支持注册多个回调；单个失败不影响其他")
    void onNewSession_supports_multiple_callbacks_and_fault_isolation() {
        java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("hi")
        );
        AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry)
                .onNewSession(fired::incrementAndGet)
                .onNewSession(() -> { throw new RuntimeException("intentional"); })
                .onNewSession(fired::incrementAndGet);

        // 反射调 fireOnNewSession 私有方法（这是为了测试隔离性）
        try {
            var method = AgentLoopHarness.class.getDeclaredMethod("fireOnNewSession");
            method.setAccessible(true);
            method.invoke(harness);
        } catch (Exception e) {
            fail("反射失败: " + e);
        }

        // 即使中间那个抛异常，前后两个都应该被执行
        assertEquals(2, fired.get(), "中间回调抛异常不应影响其他回调执行");
    }

    @Test
    @DisplayName("onNewSession 链式 API 返回 this，支持流畅注册")
    void onNewSession_returns_this_for_chaining() {
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(ResponseFixtures.endTurn("hi"));
        AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry);

        // 链式调用应当不抛异常
        AgentLoopHarness returned = harness
                .onNewSession(() -> {})
                .onNewSession(() -> {})
                .onNewSession(null);   // null 应被忽略（不崩）

        assertSame(harness, returned, "onNewSession 应返回 this");
    }

    // ────────────────────────────────────────────────────────────
    //  s07 review Bug 1：nag 注入不能造成 user 消息连续
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("nag 注入不能造成 user 消息连续（必须揉进上一条 user 而非新增）")
    void nag_should_not_create_consecutive_user_messages() {
        // 4 轮：连续 3 个非 todo 的 tool_use → 第 4 轮 end_turn
        // 第 4 轮请求构造前应该触发 nag——但**不能**插一条独立 user(reminder)，
        // 必须把 reminder 揉进上一条 user (tool_results) 里，保持 user/assistant 严格交替。
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "1"), "tu_001"),
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "2"), "tu_002"),
                ResponseFixtures.toolUse("test_tool", Map.of("arg", "3"), "tu_003"),
                ResponseFixtures.endTurn("done")
        );

        AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("do work"));

        harness.agentLoop(messages);

        // 验证 1：第 4 轮请求里 user/assistant 严格交替
        CreateMessageRequest fourthReq = mock.getRequests().get(3);
        List<MessageParam> seq = fourthReq.getMessages();
        for (int i = 0; i < seq.size() - 1; i++) {
            String currentRole = seq.get(i).getRole();
            String nextRole = seq.get(i + 1).getRole();
            assertNotEquals(currentRole, nextRole,
                    "messages[" + i + "] 和 messages[" + (i + 1) + "] 都是 " + currentRole +
                            " — 违反 Anthropic Messages API 严格交替约束");
        }

        // 验证 2：reminder 文本必须出现在最后一条 user 消息（tool_results 那条）的内容里
        MessageParam lastUserBeforeAssistant = null;
        for (MessageParam m : seq) {
            if ("user".equals(m.getRole())) lastUserBeforeAssistant = m;
        }
        assertNotNull(lastUserBeforeAssistant);

        // 这条 user 的 content 应该是 List<ContentBlock>（tool_results + 追加的 TextBlock(reminder)）
        Object content = lastUserBeforeAssistant.getContent();
        assertInstanceOf(List.class, content,
                "tool_results 那条 user 的 content 应该是 List；reminder 以 TextBlock 形式追加");

        @SuppressWarnings("unchecked")
        List<ContentBlock> blocks = (List<ContentBlock>) content;
        boolean hasReminder = blocks.stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .anyMatch(t -> t.contains("<reminder>"));
        assertTrue(hasReminder,
                "reminder 文本必须以 TextBlock 形式追加在 tool_results 末尾，实际 blocks=" + blocks);
    }

    // ────────────────────────────────────────────────────────────
    //  s07 review Bug 2：注册 onNewSession(::clearHistory) 时新会话应清空 history
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("注册 onNewSession(::clearHistory) 后，新 query 应清空跨会话 history")
    void new_session_should_clear_history_when_registered() {
        // 模拟 repl 跑两次 query：每次都 end_turn
        // query1 跑完后 history 应该非空
        // 触发新会话（fireOnNewSession 经回调 clearHistory）→ history 清空
        // query2 进来时只看到自己一条 user
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("answer 1"),
                ResponseFixtures.endTurn("answer 2")
        );

        AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry);
        // 关键：注册 history 清理回调（生产里由 fromEnv 注册）
        harness.onNewSession(harness::clearHistory);

        // query 1
        harness.processOneQuery("question 1");
        assertEquals(2, harness.getHistory().size(),
                "query1 跑完后 history = [user1, assistant1]");

        // 模拟 repl 收到 query2 之前触发新会话
        // 用反射调 fireOnNewSession（私有），这是测试 onNewSession 集成的标准做法
        try {
            var method = AgentLoopHarness.class.getDeclaredMethod("fireOnNewSession");
            method.setAccessible(true);
            method.invoke(harness);
        } catch (Exception e) {
            fail("反射 fireOnNewSession 失败: " + e);
        }

        assertEquals(0, harness.getHistory().size(),
                "fireOnNewSession 触发 clearHistory 后，history 应被清空");

        // query 2
        harness.processOneQuery("question 2");

        // 第 2 次 LLM 调用收到的 messages 只应有 1 条（query2 自己），不带 query1
        CreateMessageRequest secondReq = mock.getRequests().get(1);
        assertEquals(1, secondReq.getMessages().size(),
                "query2 的请求 messages 应该只有 1 条（onNewSession 清空了 query1 的对话）");
        assertEquals("user", secondReq.getMessages().get(0).getRole());
        assertEquals("question 2", secondReq.getMessages().get(0).getContent());
    }

    @Test
    @DisplayName("不注册 clearHistory 时，history 跨 query 累积（保留扩展空间的反向验证）")
    void without_clear_history_callback_history_accumulates() {
        // 这测试对应 plan 里"清不清 history 是可注册行为而非硬编码"——
        // 不注册回调时，processOneQuery 跨调用 history 累积，留作未来"多轮对话连续性"用。
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("answer 1"),
                ResponseFixtures.endTurn("answer 2")
        );

        AgentLoopHarness harness = new AgentLoopHarness(mock, "test-model", registry);
        // 不注册 clearHistory 回调

        harness.processOneQuery("question 1");
        harness.processOneQuery("question 2");

        // 第 2 次请求应该看到 query1 的全部对话历史
        CreateMessageRequest secondReq = mock.getRequests().get(1);
        assertEquals(3, secondReq.getMessages().size(),
                "未注册 clearHistory 时，query2 应看到 [user1, assistant1, user2] 累积历史");
    }

    // ────────────────────────────────────────────────────────────
    //  测试用 Spy Tool：记录调用次数和参数
    // ────────────────────────────────────────────────────────────

    /**
     * 一个简单的 Tool 实现，注册一个名为 "test_tool" 的工具。
     * 每次执行都记录调用次数和最后的 ToolCall，方便测试断言。
     */
    private static class SpyTestTool implements Tool {
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
