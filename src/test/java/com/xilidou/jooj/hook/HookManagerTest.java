package com.xilidou.jooj.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 HookManager 的核心行为：注册 + 短路 trigger。
 *
 * <p>对应 Python s04 的 trigger_hooks 短路语义：
 * <pre>
 *   for callback in HOOKS[event]:
 *       result = callback(*args)
 *       if result is not None: return result   # ← 短路
 *   return None
 * </pre>
 */
class HookManagerTest {

    private static final ObjectMapper JSON = JsonMappers.newMapper();

    private static ToolUseBlock toolUse(String name) {
        JsonNode input = JSON.valueToTree(Map.of("k", "v"));
        return new ToolUseBlock("tu_test", name, input);
    }

    @Test
    @DisplayName("空 manager → 所有 trigger 返回 empty")
    void empty_manager_returns_empty() {
        HookManager m = new HookManager();
        assertTrue(m.triggerUserPrompt("hi").isEmpty());
        assertTrue(m.triggerPreToolUse(toolUse("bash")).isEmpty());
        assertTrue(m.triggerPostToolUse(toolUse("bash"), "out").isEmpty());
        assertTrue(m.triggerStop(List.of()).isEmpty());
    }

    @Test
    @DisplayName("注册多个 hook，按顺序执行；第一个非空 Optional 短路")
    void short_circuits_on_first_non_empty() {
        AtomicInteger callCount = new AtomicInteger();

        Hook.OnPreToolUse h1 = tu -> {
            callCount.incrementAndGet();
            return Optional.empty();          // 第一个：放过
        };
        Hook.OnPreToolUse h2 = tu -> {
            callCount.incrementAndGet();
            return Optional.of("blocked");    // 第二个：阻止
        };
        Hook.OnPreToolUse h3 = tu -> {
            callCount.incrementAndGet();
            fail("h3 不应该被调用，h2 已经短路");
            return Optional.of("never");
        };

        HookManager m = new HookManager()
                .register(h1)
                .register(h2)
                .register(h3);

        Optional<String> result = m.triggerPreToolUse(toolUse("bash"));

        assertEquals(Optional.of("blocked"), result);
        assertEquals(2, callCount.get(), "h1 + h2 各调一次，h3 没调");
    }

    @Test
    @DisplayName("4 种 trigger 互不干扰")
    void different_event_types_isolated() {
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();

        HookManager m = new HookManager()
                .register((Hook.OnPreToolUse) tu -> { preCalls.incrementAndGet(); return Optional.empty(); })
                .register((Hook.OnPostToolUse) (tu, out) -> { postCalls.incrementAndGet(); return Optional.empty(); });

        m.triggerPreToolUse(toolUse("bash"));
        assertEquals(1, preCalls.get());
        assertEquals(0, postCalls.get(), "PostToolUse 不应被 triggerPreToolUse 触发");

        m.triggerPostToolUse(toolUse("bash"), "out");
        assertEquals(1, preCalls.get(), "Pre 数没动");
        assertEquals(1, postCalls.get());
    }

    @Test
    @DisplayName("UserPrompt hook 阻止时返回原因")
    void user_prompt_hook_can_block() {
        HookManager m = new HookManager()
                .register((Hook.OnUserPrompt) q ->
                        q.contains("password") ? Optional.of("contains sensitive word") : Optional.empty());

        assertTrue(m.triggerUserPrompt("hello").isEmpty());
        assertEquals(Optional.of("contains sensitive word"),
                m.triggerUserPrompt("my password is 123"));
    }

    @Test
    @DisplayName("Stop hook 返回非空 = 强制 loop 再来一轮")
    void stop_hook_can_force_continue() {
        // 模拟"模型说完了，但我们觉得任务没完"的场景
        HookManager m = new HookManager()
                .register((Hook.OnStop) messages ->
                        messages.size() < 3 ? Optional.of("Please elaborate") : Optional.empty());

        // 短对话：触发"再追一句"
        Optional<String> result = m.triggerStop(List.of(MessageParam.user("hi")));
        assertEquals(Optional.of("Please elaborate"), result);

        // 长对话：让它走
        assertTrue(m.triggerStop(List.of(
                MessageParam.user("hi"),
                MessageParam.user("hi2"),
                MessageParam.user("hi3"))).isEmpty());
    }

    @Test
    @DisplayName("countHooks 准确反映注册数")
    void count_hooks() {
        HookManager m = new HookManager()
                .register((Hook.OnPreToolUse) tu -> Optional.empty())
                .register((Hook.OnPreToolUse) tu -> Optional.empty())
                .register((Hook.OnUserPrompt) q -> Optional.empty());

        assertEquals(2, m.countHooks(HookEvent.PRE_TOOL_USE));
        assertEquals(1, m.countHooks(HookEvent.USER_PROMPT_SUBMIT));
        assertEquals(0, m.countHooks(HookEvent.POST_TOOL_USE));
        assertEquals(0, m.countHooks(HookEvent.STOP));
    }
}
