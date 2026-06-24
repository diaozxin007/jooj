package com.xilidou.marvis;

import com.xilidou.marvis.agent.AgentLoopHarness;
import com.xilidou.marvis.tool.ToolRegistry;
import com.xilidou.marvis.compact.CompactPipeline;
import com.xilidou.marvis.hook.HookManager;
import com.xilidou.marvis.http.AnthropicClient;
import com.xilidou.marvis.http.ResponseFixtures;
import com.xilidou.marvis.http.dto.CreateMessageRequest;
import com.xilidou.marvis.memory.MemoryService;
import com.xilidou.marvis.permission.PermissionPipeline;
import com.xilidou.marvis.skill.SkillRegistry;
import com.xilidou.marvis.subagent.Subagent;
import com.xilidou.marvis.todo.TodoStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 切片 C 端到端验证 —— Spring 容器装配后所有依赖都能正常注入。
 *
 * <p>这个测试是切片 C 的"地基":如果它通过,说明:
 * <ol>
 *   <li>{@link MarvisProperties} 从 application-test.yml 正确绑定</li>
 *   <li>{@link com.xilidou.marvis.http.HttpClientConfig#httpAuth(MarvisProperties)}
 *       的二选一逻辑工作(test 配置走 api-key 分支)</li>
 *   <li>{@link AnthropicClient} 通过 {@link MockitoBean} 替换为 mock,不打 HTTP</li>
 *   <li>{@link com.xilidou.marvis.compact.CompactConfiguration} +
 *       {@link com.xilidou.marvis.memory.MemoryConfiguration} +
 *       {@link com.xilidou.marvis.permission.PermissionConfiguration}
 *       的 5 个 @Bean 都装配成功
 *       (CompactConfig / MemoryConfig / PermissionPipeline / CompactPipeline / MemoryService)</li>
 *   <li>{@link Subagent} 的 @Lazy ToolRegistry 切断了 TaskTool↔Subagent 循环</li>
 *   <li>{@link AgentLoopHarness} 的 11 参 Spring 构造器 + @PostConstruct 都跑通</li>
 *   <li>所有 5 个 @Component Tool 被 ToolRegistry 自动收集</li>
 * </ol>
 *
 * <p>{@link MockitoBean} 是 Spring Boot 4 替代 @MockBean 的官方方案,
 * 直接把容器里 {@link AnthropicClient} Bean 替换为 Mockito mock,
 * 不需要 @Import TestConfiguration + bean override。
 */
@SpringBootTest
@ActiveProfiles("test")
class MarvisSpringIntegrationTest {

    @MockitoBean
    AnthropicClient mockClient;

    @Autowired AgentLoopHarness harness;
    @Autowired ToolRegistry toolRegistry;
    @Autowired HookManager hookManager;
    @Autowired Subagent subagent;
    @Autowired CompactPipeline compactPipeline;
    @Autowired MemoryService memoryService;
    @Autowired PermissionPipeline permissionPipeline;
    @Autowired SkillRegistry skillRegistry;
    @Autowired TodoStore todoStore;
    @Autowired MarvisProperties props;

    @Test
    @DisplayName("Spring 容器把所有 marvis 核心 Bean 都装配成功")
    void all_core_beans_are_wired() {
        assertNotNull(harness, "AgentLoopHarness 应该被注入");
        assertNotNull(toolRegistry);
        assertNotNull(hookManager);
        assertNotNull(subagent);
        assertNotNull(compactPipeline);
        assertNotNull(memoryService);
        assertNotNull(permissionPipeline);
        assertNotNull(skillRegistry);
        assertNotNull(todoStore);
    }

    @Test
    @DisplayName("application-test.yml 的 marvis.* 配置被正确绑定")
    void config_properties_bound_from_test_yaml() {
        assertEquals("test-fake-key", props.getAnthropic().getApiKey());
        assertEquals("test-model", props.getAnthropic().getModel());
        assertEquals("always-allow", props.getPermission().getMode());
    }

    @Test
    @DisplayName("ToolRegistry 自动收集了所有 @Component Tool;task 内联在 AgentLoopHarness 不在 registry")
    void tool_registry_collects_all_component_tools() {
        var tools = toolRegistry.getAllTools();
        // bash + filesystem(4) + todo + load_skill = 7 个普通工具,不含 task
        assertTrue(tools.size() >= 7,
                "Spring 应该自动收集所有 @Component Tool,实际:" + tools);

        var names = tools.stream().map(t -> t.getName()).toList();
        assertTrue(names.contains("bash"), "bash 必须自动注册");
        assertTrue(names.contains("read_file"), "read_file 必须自动注册");
        assertTrue(names.contains("todo_write"), "todo_write 必须自动注册");
        assertTrue(names.contains("load_skill"), "load_skill 必须自动注册");
        // task 工具结构性消除循环依赖后,内联到 AgentLoopHarness#buildTools(),
        // 不再出现在 ToolRegistry 里。LLM 看到 task 是因为 buildTools 手动追加。
        assertFalse(names.contains("task"),
                "task 工具已结构性消除循环 —— 不应该在 ToolRegistry 里(它是 AgentLoopHarness 内置的)");
    }

    @Test
    @DisplayName("HookManager 自动收集了所有 @Component Hook")
    void hook_manager_collects_all_component_hooks() {
        // PermissionHook + ToolUseLogHook + LargeOutputHook + MetricsHook (Pre+Post)
        // PreToolUse 应有 ≥3 个,PostToolUse ≥2 个
        assertTrue(hookManager.countHooks(com.xilidou.marvis.hook.HookEvent.PRE_TOOL_USE) >= 3,
                "PreToolUse hooks 应有 ≥3");
        assertTrue(hookManager.countHooks(com.xilidou.marvis.hook.HookEvent.POST_TOOL_USE) >= 2,
                "PostToolUse hooks 应有 ≥2");
    }

    @Test
    @DisplayName("AgentLoop 跑一轮完整对话:mock client 返回 end_turn,history 累积正确")
    void agent_loop_works_end_to_end_with_mock_client() {
        Mockito.when(mockClient.createMessage(ArgumentMatchers.any(CreateMessageRequest.class)))
                .thenReturn(ResponseFixtures.endTurn("hello from spring-wired marvis"));

        harness.processOneQuery("ping");

        assertEquals(2, harness.getHistory().size());
        assertEquals("user", harness.getHistory().get(0).getRole());
        assertEquals("assistant", harness.getHistory().get(1).getRole());

        // mockClient 至少被调用 1 次(主 LLM 调用);
        // MemoryExtractor.extract 在 onTurnEnd 也会再调一次(LLM 用于抽取 fact),
        // 所以验证 atLeastOnce,不锁定具体次数
        Mockito.verify(mockClient, Mockito.atLeastOnce())
                .createMessage(ArgumentMatchers.any(CreateMessageRequest.class));
    }

    /**
     * s10 回归:验证 turn 1 中途新写入的 memory,turn 2 立刻能进 SYSTEM prompt。
     *
     * <p>切片 C 之前 SYSTEM 是 {@code @PostConstruct} 的快照,这个 case 会失败。
     * s10 之后由 {@link com.xilidou.marvis.prompt.SystemPromptAssembler}
     * 在每轮 LLM 调用前重组装,新 memory 立刻可见。
     */
    @Test
    @DisplayName("s10: turn 1 写入 memory → turn 2 SYSTEM prompt 应包含它")
    void memory_written_in_turn1_is_visible_in_turn2_system() throws Exception {
        // ── Setup:绕过真实 LLM 抽取,直接调 MemoryStore.write 模拟 turn 1 写入 ──
        // 用 reflection 拿到包私有的 MemoryStore
        var memoryFieldRef = harness.getClass().getDeclaredField("memoryService");
        memoryFieldRef.setAccessible(true);
        Object serviceInstance = memoryFieldRef.get(harness);

        var storeMethod = serviceInstance.getClass().getDeclaredMethod("store");
        storeMethod.setAccessible(true);
        Object store = storeMethod.invoke(serviceInstance);

        var memoryFileClass = Class.forName(
                "com.xilidou.marvis.memory.MemoryFile");
        var memoryTypeClass = Class.forName(
                "com.xilidou.marvis.memory.MemoryFile$Type");
        var userType = Enum.valueOf((Class<Enum>) memoryTypeClass.asSubclass(Enum.class), "USER");

        var ofMethod = memoryFileClass.getDeclaredMethod(
                "of", String.class, memoryTypeClass, String.class, String.class);
        Object mem = ofMethod.invoke(null,
                "s10-test-fact",
                userType,
                "User prefers Java for backend.",
                "User prefers Java for backend services."
        );
        var writeMethod = store.getClass().getDeclaredMethod("write", memoryFileClass);
        writeMethod.invoke(store, mem);

        var rebuildMethod = store.getClass().getDeclaredMethod("rebuildIndex");
        rebuildMethod.invoke(store);

        try {
            // ── 跑一轮普通 query,SYSTEM 应已含新 memory ──
            Mockito.when(mockClient.createMessage(ArgumentMatchers.any(CreateMessageRequest.class)))
                    .thenReturn(ResponseFixtures.endTurn("ack"));

            harness.clearHistory();
            harness.processOneQuery("any query after memory write");

            // 用 ArgumentCaptor 抓所有调用的 request,检查 SYSTEM
            var captor = ArgumentCaptor.forClass(CreateMessageRequest.class);
            Mockito.verify(mockClient, Mockito.atLeastOnce())
                    .createMessage(captor.capture());

            boolean foundMemoryInSystem = captor.getAllValues().stream()
                    .map(CreateMessageRequest::getSystemText)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(sys -> sys.contains("s10-test-fact")
                            || sys.contains("User prefers Java"));

            assertTrue(foundMemoryInSystem,
                    "turn 1 写入的 memory 应该出现在 turn 2 的 SYSTEM prompt 里 —— " +
                            "如果失败说明 SystemPromptAssembler 没每轮重读 MemoryService.catalog()");
        } finally {
            // 清理:删除测试 memory,避免污染其他测试
            var deleteMethod = store.getClass().getDeclaredMethod("delete", String.class);
            deleteMethod.invoke(store, "s10-test-fact.md");
            rebuildMethod.invoke(store);
        }
    }
}
