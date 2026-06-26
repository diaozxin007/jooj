package com.xilidou.jooj.hook;

import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hook 注册表 + trigger 调度。
 *
 * <p>对应 Python s04：
 * <pre>
 *   HOOKS = {"UserPromptSubmit": [], "PreToolUse": [], ...}
 *   register_hook(event, callback)
 *   trigger_hooks(event, *args)
 * </pre>
 *
 * <h3>注册方式（两种并存）</h3>
 *
 * <ol>
 *   <li><b>Spring 自动注入</b>（推荐）：
 *       构造器注入 4 个 List，Spring 容器自动找所有标记 {@code @Component} 的 hook 实现。
 *       加新 hook 只要新写一个类加 {@code @Component}，HookManager 不用动。
 *
 *   <li><b>手工注册</b>（测试 / 独立 main）：
 *       用空构造器创建后调用 {@link #register} 系列方法。
 * </ol>
 *
 * <h3>触发短路语义</h3>
 *
 * <p>{@link #triggerPreToolUse} 等方法按注册顺序遍历 hook，第一个返回**非空** Optional 的
 * hook 立即终止后续调用，返回该 Optional。这对应 Python 的 {@code if result is not None: return result}。
 *
 * <p>设计后果：注册顺序很重要。比如 PreToolUse 上挂了 {@code [permissionHook, logHook]}，
 * 当 permissionHook 阻止时 logHook 不会执行——这一般是想要的行为（被拒的 tool 不必再 log）。
 * 反之 {@code [logHook, permissionHook]} 则永远先 log 再判权限。
 */
@Component
@Slf4j
public class HookManager {

    private final Map<HookEvent, List<Object>> hooks = new EnumMap<>(HookEvent.class);

    /**
     * Spring 友好构造器：自动注入所有 {@code @Component} 的 hook 实现。
     *
     * <p>4 个参数都允许 null / empty（项目不要求一定有 hook）。
     */
    @Autowired
    public HookManager(
            List<Hook.OnUserPrompt> userPromptHooks,
            List<Hook.OnPreToolUse> preToolHooks,
            List<Hook.OnPostToolUse> postToolHooks,
            List<Hook.OnStop> stopHooks) {

        for (HookEvent e : HookEvent.values()) {
            hooks.put(e, new ArrayList<>());
        }

        if (userPromptHooks != null) userPromptHooks.forEach(this::register);
        if (preToolHooks != null) preToolHooks.forEach(this::register);
        if (postToolHooks != null) postToolHooks.forEach(this::register);
        if (stopHooks != null) stopHooks.forEach(this::register);

        log.info("HookManager initialized: {} UserPrompt, {} PreTool, {} PostTool, {} Stop",
                hooks.get(HookEvent.USER_PROMPT_SUBMIT).size(),
                hooks.get(HookEvent.PRE_TOOL_USE).size(),
                hooks.get(HookEvent.POST_TOOL_USE).size(),
                hooks.get(HookEvent.STOP).size());
    }

    /**
     * 测试 / 独立 main 构造器：空 manager，需要手工 {@link #register}。
     */
    public HookManager() {
        this(List.of(), List.of(), List.of(), List.of());
    }

    // ── 注册 API ───────────────────────────────────────────────

    public HookManager register(Hook.OnUserPrompt hook) {
        hooks.get(HookEvent.USER_PROMPT_SUBMIT).add(hook);
        return this;
    }

    public HookManager register(Hook.OnPreToolUse hook) {
        hooks.get(HookEvent.PRE_TOOL_USE).add(hook);
        return this;
    }

    public HookManager register(Hook.OnPostToolUse hook) {
        hooks.get(HookEvent.POST_TOOL_USE).add(hook);
        return this;
    }

    public HookManager register(Hook.OnStop hook) {
        hooks.get(HookEvent.STOP).add(hook);
        return this;
    }

    // ── trigger API ────────────────────────────────────────────

    /**
     * 触发 UserPromptSubmit 事件。第一个返回非空 Optional 的 hook 短路。
     */
    public Optional<String> triggerUserPrompt(String query) {
        for (Object h : hooks.get(HookEvent.USER_PROMPT_SUBMIT)) {
            Optional<String> result = ((Hook.OnUserPrompt) h).handle(query);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    /**
     * 触发 PreToolUse 事件——s04 的核心。permission 检查 / log / metric 都通过这里。
     */
    public Optional<String> triggerPreToolUse(ToolUseBlock toolUse) {
        for (Object h : hooks.get(HookEvent.PRE_TOOL_USE)) {
            Optional<String> result = ((Hook.OnPreToolUse) h).handle(toolUse);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    /**
     * 触发 PostToolUse 事件。
     */
    public Optional<String> triggerPostToolUse(ToolUseBlock toolUse, String output) {
        for (Object h : hooks.get(HookEvent.POST_TOOL_USE)) {
            Optional<String> result = ((Hook.OnPostToolUse) h).handle(toolUse, output);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    /**
     * 触发 Stop 事件。返回非空意味着"loop 再来一轮"——见 {@link Hook.OnStop}。
     */
    public Optional<String> triggerStop(List<MessageParam> messages) {
        for (Object h : hooks.get(HookEvent.STOP)) {
            Optional<String> result = ((Hook.OnStop) h).handle(messages);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    // ── 观察 API（测试用）──────────────────────────────────────

    public int countHooks(HookEvent event) {
        return hooks.get(event).size();
    }
}
