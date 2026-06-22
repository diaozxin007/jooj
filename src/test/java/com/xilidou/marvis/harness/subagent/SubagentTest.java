package com.xilidou.marvis.harness.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.harness.JacksonConfig;
import com.xilidou.marvis.harness.base.SkillRegistry;
import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.hook.HookManager;
import com.xilidou.marvis.harness.http.MockAnthropicClient;
import com.xilidou.marvis.harness.http.ResponseFixtures;
import com.xilidou.marvis.harness.http.dto.CreateMessageRequest;
import com.xilidou.marvis.harness.http.dto.InputSchema;
import com.xilidou.marvis.harness.http.dto.MessageParam;
import com.xilidou.marvis.harness.http.dto.ToolDef;
import com.xilidou.marvis.harness.skill.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 Subagent 的核心行为：
 * <ul>
 *   <li>fresh context：messages 从 task description 开始，没有父 Agent 历史</li>
 *   <li>独立 SYSTEM prompt（不含 task 工具，防递归）</li>
 *   <li>excludedTools 过滤生效：task / todo_write 不传给子 Agent</li>
 *   <li>只返回最后 assistant text，中间过程丢弃</li>
 *   <li>max turns 安全限制</li>
 *   <li>permission hook 也作用于子 Agent</li>
 * </ul>
 */
class SubagentTest {

    private static final ObjectMapper JSON = JacksonConfig.newMapper();

    private SkillRegistry registry;
    private HookManager hooks;
    private SpyTool spyTool;

    @BeforeEach
    void setUp() {
        spyTool = new SpyTool();
        // 模拟父 Agent 拥有的工具集：task + todo_write 应被 excludedTools 过滤
        registry = new SkillRegistry();
        registry.load(spyTool);
        registry.load(new FakeNamedSkill("task"));        // 子 Agent 应该看不到
        registry.load(new FakeNamedSkill("todo_write"));  // 子 Agent 应该看不到
        hooks = new HookManager();
    }

    private Subagent newSubagent(MockAnthropicClient mock) {
        return new Subagent(mock, "test-model", registry, JSON, hooks);
    }

    @Test
    @DisplayName("fresh context: messages 起点是 task description，不含父历史")
    void fresh_context() {
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("done")
        );
        Subagent sub = newSubagent(mock);
        sub.spawn("分析 X 模块");

        // 子 Agent 第一次请求时，messages 应该只有 task 描述这 1 条
        CreateMessageRequest firstReq = mock.getRequests().get(0);
        assertEquals(1, firstReq.getMessages().size(),
                "子 Agent 应该从 fresh context 开始，只含 task 描述");
        assertEquals("user", firstReq.getMessages().get(0).getRole());
        assertEquals("分析 X 模块", firstReq.getMessages().get(0).getContent());
    }

    @Test
    @DisplayName("excludedTools 过滤：task / todo_write 不出现在子 Agent 工具集")
    void excluded_tools_filtered() {
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("done")
        );
        Subagent sub = newSubagent(mock);
        sub.spawn("任务");

        CreateMessageRequest req = mock.getRequests().get(0);
        List<String> toolNames = req.getTools().stream().map(ToolDef::getName).toList();

        assertTrue(toolNames.contains("spy_tool"), "spy_tool 应该可用");
        assertFalse(toolNames.contains("task"), "task 工具必须被排除（防递归）");
        assertFalse(toolNames.contains("todo_write"), "todo_write 必须被排除（防污染父 store）");
    }

    @Test
    @DisplayName("自定义 SYSTEM prompt：包含 'Do not delegate further'")
    void uses_sub_system_prompt() {
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("done")
        );
        Subagent sub = newSubagent(mock);
        sub.spawn("任务");

        CreateMessageRequest req = mock.getRequests().get(0);
        assertNotNull(req.getSystem());
        assertTrue(req.getSystem().contains("Do not delegate further"),
                "子 Agent 用专门的 SYSTEM prompt，明确禁止再递归 delegate");
        assertTrue(req.getSystem().contains("concise summary"),
                "应该明确要求返回精简摘要");
    }

    @Test
    @DisplayName("正常流程：tool_use → exec → end_turn，返回最后 text")
    void normal_flow() {
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.toolUse("spy_tool", Map.of("arg", "v"), "tu_001"),
                ResponseFixtures.endTurn("Final answer: 42")
        );
        Subagent sub = newSubagent(mock);
        String result = sub.spawn("找答案");

        assertEquals(1, spyTool.executionCount.get(), "工具应该被执行一次");
        assertEquals("Final answer: 42", result);
    }

    @Test
    @DisplayName("permission hook 也作用于子 Agent")
    void permission_hook_applies_to_subagent() {
        // hook：阻止所有 spy_tool 调用
        hooks.register((com.xilidou.marvis.harness.hook.Hook.OnPreToolUse) toolUse ->
                "spy_tool".equals(toolUse.getName())
                        ? java.util.Optional.of("blocked by sub-permission")
                        : java.util.Optional.empty());

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.toolUse("spy_tool", Map.of("arg", "v"), "tu_001"),
                ResponseFixtures.endTurn("OK, gave up")
        );
        Subagent sub = newSubagent(mock);
        String result = sub.spawn("找答案");

        assertEquals(0, spyTool.executionCount.get(),
                "hook 阻止时子 Agent 也不应执行工具——permission 必须作用于子 Agent");
        assertEquals("OK, gave up", result);
    }

    @Test
    @DisplayName("max turns 安全限制：跑满 30 轮还没自然停 → 返回 fallback")
    void max_turns_safety() {
        // 永远返回 tool_use（造成 infinite loop）
        // 注意：MockAnthropicClient 用 ofResponses 是按顺序消费，跑完会抛
        // 改用动态 responder：永远返回相同 tool_use
        com.xilidou.marvis.harness.http.MockAnthropicClient mock =
                new com.xilidou.marvis.harness.http.MockAnthropicClient(req ->
                        ResponseFixtures.toolUse("spy_tool", Map.of(), "tu_xxx"));

        Subagent sub = newSubagent(mock);
        String result = sub.spawn("永远跑下去");

        // 跑了恰好 MAX_TURNS = 30 轮
        assertEquals(Subagent.MAX_TURNS, mock.getCallCount(),
                "应该跑满 30 轮就停（防 infinite loop）");
        // 子 Agent 没吐 text，返回 fallback 提示
        assertTrue(result.contains("30 turns") || result.contains("MAX_TURNS")
                        || result.contains("without final answer"),
                "跑满 max turns 应该返回 fallback 提示，实际：" + result);
    }

    @Test
    @DisplayName("空任务描述 → 友好错误")
    void empty_description() {
        MockAnthropicClient mock = MockAnthropicClient.ofResponses();
        Subagent sub = newSubagent(mock);
        String result = sub.spawn("");

        assertTrue(result.contains("error") || result.toLowerCase().contains("empty"),
                "空任务描述应返回错误，实际：" + result);
        assertEquals(0, mock.getCallCount(), "空描述时不应该调 LLM");
    }

    @Test
    @DisplayName("多 tool_use 同轮：按顺序执行")
    void multiple_tool_uses_in_one_turn() {
        var tu1 = ResponseFixtures.makeToolUse("spy_tool", Map.of("arg", "1"), "tu_001");
        var tu2 = ResponseFixtures.makeToolUse("spy_tool", Map.of("arg", "2"), "tu_002");

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.multipleToolUse(List.of(tu1, tu2)),
                ResponseFixtures.endTurn("done")
        );
        Subagent sub = newSubagent(mock);
        sub.spawn("two-step task");

        assertEquals(2, spyTool.executionCount.get(), "两个工具调用都应执行");
    }

    // ── helper Skills ──────────────────────────────────────────

    /** 真实工具：记录调用次数，返回 ok */
    private static class SpyTool implements Skill {
        final AtomicInteger executionCount = new AtomicInteger();

        @Override public String getName() { return "spy"; }
        @Override public String getDescription() { return "Spy tool"; }

        @Override
        public List<ToolDefinition> getTools() {
            return List.of(new ToolDefinition(
                    "spy_tool", "spy",
                    InputSchema.object(
                            Map.of("arg", Map.of("type", "string", "description", "any")),
                            "arg")));
        }

        @Override
        public ToolResult execute(ToolCall call) {
            executionCount.incrementAndGet();
            return new ToolResult(true, "ok");
        }
    }

    /** 假名字 Skill：用来验证 excludedTools 过滤 */
    private static class FakeNamedSkill implements Skill {
        private final String name;
        FakeNamedSkill(String name) { this.name = name; }

        @Override public String getName() { return name + "_skill"; }
        @Override public String getDescription() { return "fake"; }

        @Override
        public List<ToolDefinition> getTools() {
            return List.of(new ToolDefinition(
                    name, "fake",
                    InputSchema.object(Map.of("x", Map.of("type", "string")), "x")));
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return new ToolResult(false, "should not be called");
        }
    }
}
