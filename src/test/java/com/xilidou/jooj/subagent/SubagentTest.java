package com.xilidou.jooj.subagent;

import com.xilidou.jooj.JoojTestConfig;
import com.xilidou.jooj.agent.AgentControl;
import com.xilidou.jooj.agent.AgentInterruptedException;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import com.xilidou.jooj.hook.HookManager;
import com.xilidou.jooj.http.MockAnthropicClient;
import com.xilidou.jooj.http.ResponseFixtures;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.http.dto.ToolDef;
import com.xilidou.jooj.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link Subagent} 的核心行为:
 * <ul>
 *   <li>fresh context:messages 从 task description 开始,没有父 Agent 历史</li>
 *   <li>独立 SYSTEM prompt(不含 task 工具,防递归)</li>
 *   <li>**显式白名单过滤生效**(s12 Stage 3):只暴露 SUB_TOOLS 集合,task / todo_write
 *       / spy_tool 这些不在白名单的工具不传给子 Agent</li>
 *   <li>只返回最后 assistant text,中间过程丢弃</li>
 *   <li>max turns 安全限制</li>
 *   <li>permission hook 也作用于子 Agent</li>
 * </ul>
 *
 * <p>切片 C 后:走 {@link SpringBootTest},生产代码 Subagent 只剩单一 Spring 构造器,
 * 测试由 Spring 测试框架装配。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({JoojTestConfig.class, SubagentTest.SpyToolsTestConfig.class})
class SubagentTest {

    @Autowired Subagent subagent;
    @Autowired MockAnthropicClient mock;
    @Autowired HookManager hooks;
    @Autowired SpyTool spyTool;
    @Autowired AgentControl agentControl;

    @BeforeEach
    void setUp() {
        spyTool.reset();
        agentControl.clearInterrupt("d9-parent-sid");
    }

    @Test
    @DisplayName("fresh context: messages 起点是 task description,不含父历史")
    void fresh_context() {
        mock.reset(ResponseFixtures.endTurn("done"));

        subagent.spawn("分析 X 模块");

        CreateMessageRequest firstReq = mock.getRequests().get(0);
        assertEquals(1, firstReq.getMessages().size(),
                "子 Agent 应该从 fresh context 开始,只含 task 描述");
        assertEquals("user", firstReq.getMessages().get(0).getRole());
        assertEquals("分析 X 模块", firstReq.getMessages().get(0).getContent());
    }

    @Test
    @DisplayName("白名单过滤(s12 Stage 3):只暴露 SUB_TOOLS,task / todo_write / spy_tool 都被排除")
    void whitelist_filters_to_default_included_tools_only() {
        mock.reset(ResponseFixtures.endTurn("done"));

        subagent.spawn("任务");

        CreateMessageRequest req = mock.getRequests().get(0);
        List<String> toolNames = req.getTools().stream().map(ToolDef::getName).toList();

        // 白名单里只有 5 个工具(bash / read_file / write_file / edit_file / glob)
        // 这些是 ToolRegistry 里 BashTool + FileSystemTool 的注册项,实际容器有
        assertTrue(toolNames.contains("bash"), "bash 在白名单,应该可见");
        assertTrue(toolNames.contains("read_file"), "read_file 在白名单,应该可见");
        // task / todo_write / spy_tool / load_skill / 5 个 tasks 系列,都不在白名单
        assertFalse(toolNames.contains("task"),
                "task 不在 SUB_TOOLS 白名单(防递归),应该被过滤");
        assertFalse(toolNames.contains("todo_write"),
                "todo_write 不在 SUB_TOOLS 白名单(防污染父 store),应该被过滤");
        assertFalse(toolNames.contains("spy_tool"),
                "spy_tool 不在 SUB_TOOLS 白名单(默认安全),应该被过滤");
        assertFalse(toolNames.contains("create_task"),
                "s12 任务系列工具不在白名单(子 Agent 不该自己管 task 状态)");
        assertFalse(toolNames.contains("load_skill"),
                "load_skill 不在白名单(子 Agent 简化:不再 lazy-load skill)");
    }

    @Test
    @DisplayName("自定义 SYSTEM prompt:包含 'Do not delegate further'")
    void uses_sub_system_prompt() {
        mock.reset(ResponseFixtures.endTurn("done"));

        subagent.spawn("任务");

        CreateMessageRequest req = mock.getRequests().get(0);
        assertNotNull(req.getSystem());
        assertTrue(req.getSystemText().contains("Do not delegate further"),
                "子 Agent 用专门的 SYSTEM prompt,明确禁止再递归 delegate");
        assertTrue(req.getSystemText().contains("concise summary"),
                "应该明确要求返回精简摘要");
    }

    @Test
    @DisplayName("正常流程:tool_use → exec → end_turn,返回最后 text")
    void normal_flow() {
        mock.reset(
                ResponseFixtures.toolUse("spy_tool", Map.of("arg", "v"), "tu_001"),
                ResponseFixtures.endTurn("Final answer: 42")
        );

        String result = subagent.spawn("找答案");

        assertEquals(1, spyTool.executionCount.get(), "工具应该被执行一次");
        assertEquals("Final answer: 42", result);
    }

    @Test
    @DisplayName("max turns 安全限制:跑满 30 轮还没自然停 → 返回 fallback")
    void max_turns_safety() {
        mock.reset(req -> ResponseFixtures.toolUse("spy_tool", Map.of(), "tu_xxx"));

        String result = subagent.spawn("永远跑下去");

        assertEquals(Subagent.MAX_TURNS, mock.getCallCount(),
                "应该跑满 30 轮就停(防 infinite loop)");
        assertTrue(result.contains("30 turns") || result.contains("MAX_TURNS")
                        || result.contains("without final answer"),
                "跑满 max turns 应该返回 fallback 提示,实际:" + result);
    }

    @Test
    @DisplayName("空任务描述 → 友好错误")
    void empty_description() {
        // 不重置 mock(空描述路径不应调 LLM)
        mock.reset(req -> {
            throw new IllegalStateException("should not be called");
        });

        String result = subagent.spawn("");

        assertTrue(result.contains("error") || result.toLowerCase().contains("empty"),
                "空任务描述应返回错误,实际:" + result);
        assertEquals(0, mock.getCallCount(), "空描述时不应该调 LLM");
    }

    @Test
    @DisplayName("多 tool_use 同轮:按顺序执行")
    void multiple_tool_uses_in_one_turn() {
        var tu1 = ResponseFixtures.makeToolUse("spy_tool", Map.of("arg", "1"), "tu_001");
        var tu2 = ResponseFixtures.makeToolUse("spy_tool", Map.of("arg", "2"), "tu_002");

        mock.reset(
                ResponseFixtures.multipleToolUse(List.of(tu1, tu2)),
                ResponseFixtures.endTurn("done")
        );

        subagent.spawn("two-step task");

        assertEquals(2, spyTool.executionCount.get(), "两个工具调用都应执行");
    }

    // ── s22 D-9:interrupt 检查点测试 ─────────────────────────

    @Test
    @DisplayName("D-9 无 SessionContext 绑定时 interrupt registry 里的 flag 也不生效(无 sid 无法检查)")
    void interrupt_disabled_when_no_session_context() {
        mock.reset(ResponseFixtures.endTurn("done"));
        // 即使 registry 里有 flag,只要 SessionContext 没 push sid,subagent 就没法检查
        agentControl.requestInterrupt("d9-parent-sid");

        // 无 SessionContext.push → checkInterrupt 静默跳过
        String result = subagent.spawn("normal task");
        assertEquals("done", result);
    }

    @Test
    @DisplayName("D-9 SessionContext 绑定 + 未 request → 正常完成")
    void with_session_context_but_no_interrupt_completes_normally() {
        mock.reset(ResponseFixtures.endTurn("all good"));
        assertFalse(agentControl.isInterruptRequested("d9-parent-sid"));

        String prev = com.xilidou.jooj.agent.SessionContext.push("d9-parent-sid");
        try {
            String result = subagent.spawn("some task");
            assertEquals("all good", result);
        } finally {
            com.xilidou.jooj.agent.SessionContext.pop(prev);
        }
    }

    @Test
    @DisplayName("D-9 while 顶部检查点:进入第一轮 turn 前 request → 立即抛 AgentInterruptedException")
    void interrupt_at_turn_top_before_first_llm_call() {
        // 提前 request + push SessionContext,subagent 一进 for 循环顶部就应该抛
        agentControl.requestInterrupt("d9-parent-sid");
        mock.reset(req -> {
            throw new IllegalStateException("不该发起 LLM 请求,应先命中 turn 顶部检查点");
        });

        String prev = com.xilidou.jooj.agent.SessionContext.push("d9-parent-sid");
        try {
            AgentInterruptedException aie = assertThrows(AgentInterruptedException.class,
                    () -> subagent.spawn("some task"));
            assertEquals("d9-parent-sid", aie.getSessionId());

            // **关键**:flag 应该保留 —— subagent 用 isInterruptRequested(只读),让 lead 消费
            assertTrue(agentControl.isInterruptRequested("d9-parent-sid"),
                    "subagent 只读检查,flag 应保留给 lead 消费");
        } finally {
            com.xilidou.jooj.agent.SessionContext.pop(prev);
        }
    }

    @Test
    @DisplayName("D-9 tool 之间检查点:第 1 个 tool 跑完后 request → 第 2 个 tool 前抛")
    void interrupt_between_tools() {
        AtomicInteger toolCount = new AtomicInteger();
        var toolAction = new Runnable() {
            @Override public void run() {
                if (toolCount.incrementAndGet() == 1) {
                    agentControl.requestInterrupt("d9-parent-sid");
                }
            }
        };
        spyTool.beforeExecute = toolAction;

        mock.reset(
                ResponseFixtures.toolUse("spy_tool", Map.of("arg", "1"), "tu_001"),
                ResponseFixtures.endTurn("shouldnt reach")
        );

        String prev = com.xilidou.jooj.agent.SessionContext.push("d9-parent-sid");
        try {
            AgentInterruptedException aie = assertThrows(AgentInterruptedException.class,
                    () -> subagent.spawn("run tool then continue"));
            assertEquals("d9-parent-sid", aie.getSessionId());
            // spy_tool 应该跑过 1 次(第一个 tool_use),然后下一轮 turn 顶部抛
            assertEquals(1, spyTool.executionCount.get());
            // 应该只调用 1 次 LLM(第 2 次会在 turn 顶部被拦)
            assertEquals(1, mock.getCallCount());
        } finally {
            com.xilidou.jooj.agent.SessionContext.pop(prev);
        }
    }

    // ── 测试用 Spy Tool + 假名字工具(用来验证 excludedTools)──────────────

    @TestConfiguration
    static class SpyToolsTestConfig {
        @Bean SpyTool spyTool() { return new SpyTool(); }

        /**
         * 名字叫 "task" 的假 tool,用来验证子 agent 把它过滤掉。
         */
        @Bean Tool fakeTaskTool() { return new FakeNamedTool("task"); }

        /**
         * 名字叫 "todo_write" 的假 tool,用来验证子 agent 把它过滤掉。
         * 注意:容器里已经有真的 TodoTool 提供 todo_write,
         * 但 Subagent.excludedTools 是按名字过滤,真假都会被过滤,所以测试不冲突。
         */
        @Bean Tool fakeTodoWriteTool() { return new FakeNamedTool("todo_write_fake"); }
    }

    /** 真实工具:记录调用次数,返回 ok */
    static class SpyTool implements Tool {
        final AtomicInteger executionCount = new AtomicInteger();
        /** s22 D-9:测试用副作用,execute 前调 —— 让测试可以在 tool 执行边界触发副作用(如 request interrupt) */
        Runnable beforeExecute = null;

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
            if (beforeExecute != null) beforeExecute.run();
            executionCount.incrementAndGet();
            return new ToolResult(true, "ok");
        }

        void reset() { executionCount.set(0); beforeExecute = null; }
    }

    /** 假名字 Tool:用来验证 excludedTools 过滤 */
    static class FakeNamedTool implements Tool {
        private final String name;
        FakeNamedTool(String name) { this.name = name; }

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
