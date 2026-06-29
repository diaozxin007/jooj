package com.xilidou.jooj.subagent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teammate.trimWindow 的回归测试 —— 防止再次出现 s20 Demo 8 的 API 400:
 * {@code messages.0.content.0: unexpected `tool_use_id` found in `tool_result` blocks}.
 *
 * <p>纯函数测试,不需要 Spring 启动。
 */
class TeammateTrimWindowTest {

    private static MessageParam userText(String text) {
        return MessageParam.user(text);
    }

    /** 模拟 LLM tool_use 那一轮 assistant message */
    private static MessageParam assistantToolUse(String toolUseId) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("k", "v");
        return MessageParam.assistant(List.of(
                new ToolUseBlock(toolUseId, "test_tool", input)
        ));
    }

    /** 模拟 user-tool_result 那条(content 是 list 含 ToolResultBlock) */
    private static MessageParam userToolResult(String toolUseId) {
        return MessageParam.toolResults(List.of(
                new ToolResultBlock(toolUseId, "ok")
        ));
    }

    /** 模拟 assistant text 回复(无 tool_use,纯 TextBlock list) */
    private static MessageParam assistantText(String text) {
        return MessageParam.assistant(List.of(new TextBlock(text)));
    }

    @Test
    @DisplayName("messages 短于窗口 → 原样返回")
    void short_messages_return_as_is() {
        List<MessageParam> msgs = List.of(
                userText("q1"), assistantText("a1")
        );
        List<MessageParam> result = Teammate.trimWindow(msgs, 20);
        assertSame(msgs, result, "短列表应该直接返回引用,不复制");
    }

    @Test
    @DisplayName("窗口切点落在 user-tool_result 上 → 必须回退,window[0] 不能是孤儿 tool_result")
    void window_cut_at_tool_result_should_rewind() {
        List<MessageParam> msgs = new ArrayList<>(List.of(
                userText("task1"),                  // 0
                assistantToolUse("tu_001"),         // 1
                userToolResult("tu_001"),           // 2  ← 想让窗口正好从这开头(=> size-window=2 → window=5,size=7)
                assistantText("answer1"),           // 3
                userText("task2"),                  // 4
                assistantToolUse("tu_002"),         // 5
                userToolResult("tu_002")            // 6
        ));
        List<MessageParam> result = Teammate.trimWindow(msgs, 5);

        assertEquals("user", result.get(0).getRole(), "window 必须以 user 开头");
        assertTrue(Teammate.isSafeStart(result.get(0)),
                "window[0] 必须 isSafeStart;实际 = " + result.get(0).getContent());
    }

    @Test
    @DisplayName("窗口切点落在 assistant 上 → 必须回退到 user")
    void window_cut_at_assistant_should_rewind_to_user() {
        // 构造一段交替的 user/assistant,长度 20
        List<MessageParam> msgs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            msgs.add(userText("q" + i));
            msgs.add(assistantText("a" + i));
        }
        // 窗口 5 → 理想切点 = 20-5 = 15(assistant)→ 必须回退到 14(user)
        List<MessageParam> result = Teammate.trimWindow(msgs, 5);

        assertEquals("user", result.get(0).getRole(), "window 必须以 user 开头");
    }

    @Test
    @DisplayName("isSafeStart:user-text-only OK,user-tool_result 不 OK,assistant 不 OK")
    void is_safe_start_classification() {
        assertTrue(Teammate.isSafeStart(userText("hi")),
                "纯文本 user 可作起点");
        assertFalse(Teammate.isSafeStart(userToolResult("tu_x")),
                "含 ToolResultBlock 的 user 是孤儿,不可作起点");
        assertFalse(Teammate.isSafeStart(assistantText("hi")),
                "assistant 任何形态都不可作起点");
        assertFalse(Teammate.isSafeStart(assistantToolUse("tu_x")),
                "assistant tool_use 也不可作起点");
    }

    @Test
    @DisplayName("回归 s20 Demo 8:bob 第 12 轮的 messages.0.content.0 错误场景")
    void regression_s20_demo8_messages_0_orphan_tool_result() {
        // 重现 bob 当时的 history 形状(简化版)
        List<MessageParam> msgs = new ArrayList<>(List.of(
                userText("init"),                 // 0
                assistantText("ok"),              // 1
                userText("phase1"),               // 2
                assistantToolUse("tu_a"),         // 3
                userToolResult("tu_a"),           // 4
                assistantText("phase1 done"),     // 5
                userText("phase2"),               // 6
                assistantToolUse("tu_b"),         // 7
                userToolResult("tu_b"),           // 8
                assistantText("ok2"),             // 9
                userText("phase3"),               // 10
                assistantToolUse("tu_c"),         // 11
                userToolResult("tu_c")            // 12
        ));
        // size=13,window=6 → start=7(assistant tool_use)→ 必须退到 6(user-text)
        List<MessageParam> result = Teammate.trimWindow(msgs, 6);

        assertTrue(Teammate.isSafeStart(result.get(0)),
                "回归保护:trimWindow 后第一条必须是安全 user 起点;实际 role=" +
                        result.get(0).getRole() + " content=" + result.get(0).getContent());
        assertTrue(result.size() >= 6 && result.size() <= 8,
                "回退保留量应在合理范围,实际 size=" + result.size());
    }
}
