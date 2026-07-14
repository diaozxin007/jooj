package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.agent.AgentControl;
import com.xilidou.jooj.agent.DefaultAgentControl;
import com.xilidou.jooj.agent.SessionContext;
import com.xilidou.jooj.agent.control.ChoiceAnswer;
import com.xilidou.jooj.agent.control.ClarifyQuestion;
import com.xilidou.jooj.agent.control.PendingQuestion;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * s22 AQ:{@link AskUserQuestionTool} 单测,覆盖:
 * <ol>
 *   <li>正常流程:LLM 调 tool → agent 挂起 → user answer → tool_result 是 JSON</li>
 *   <li>多问题 + multiSelect</li>
 *   <li>无 sid context → Error(不挂起)</li>
 *   <li>参数校验:空 questions / options < 2 / header 超长</li>
 *   <li>timeout 保守失败(短 timeout)</li>
 *   <li>summary 摘要</li>
 * </ol>
 */
class AskUserQuestionToolTest {

    @AfterEach
    void cleanupContext() {
        SessionContext.pop(null);
    }

    private ToolCall sampleCall() {
        return new ToolCall("ask_user_question", Map.of(
                "questions", List.of(
                        Map.of(
                                "question", "用哪个 UI 库?",
                                "header", "UI lib",
                                "options", List.of(
                                        Map.of("label", "React", "description", "生态最大"),
                                        Map.of("label", "Vue")
                                )
                        )
                )
        ));
    }

    @Test
    @DisplayName("正常流程:tool 调用 → 挂起 → REST answer → tool_result 是 JSON 选择")
    void happy_path_returns_json() throws Exception {
        AgentControl control = new DefaultAgentControl();
        AskUserQuestionTool tool = new AskUserQuestionTool(control, Duration.ofSeconds(5));

        AtomicReference<ToolResult> result = new AtomicReference<>();
        CompletableFuture<Void> agent = CompletableFuture.runAsync(() -> {
            SessionContext.push("sid-aq-1");
            try {
                result.set(tool.execute(sampleCall()));
            } finally {
                SessionContext.pop(null);
            }
        });

        // 等挂起
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline && control.listPending("sid-aq-1").isEmpty()) {
            Thread.sleep(10);
        }
        List<PendingQuestion> pending = control.listPending("sid-aq-1");
        assertEquals(1, pending.size());
        assertTrue(pending.get(0) instanceof ClarifyQuestion);
        ClarifyQuestion cq = (ClarifyQuestion) pending.get(0);
        assertEquals(1, cq.questions().size());
        assertEquals("用哪个 UI 库?", cq.questions().get(0).question());
        assertEquals("UI lib", cq.questions().get(0).header());
        assertEquals(2, cq.questions().get(0).options().size());

        // REST 层 answer
        assertTrue(control.answer("sid-aq-1", cq.askId(),
                new ChoiceAnswer(Map.of("0", List.of("React")))));

        agent.get(2, TimeUnit.SECONDS);
        ToolResult r = result.get();
        assertTrue(r.isSuccess());
        // JSON 应含选择
        assertTrue(r.getOutput().contains("\"0\""));
        assertTrue(r.getOutput().contains("\"React\""));
    }

    @Test
    @DisplayName("多问题 + multiSelect:JSON 包含所有 sub-question 的答复")
    void multi_question_multi_select() throws Exception {
        AgentControl control = new DefaultAgentControl();
        AskUserQuestionTool tool = new AskUserQuestionTool(control, Duration.ofSeconds(5));

        ToolCall call = new ToolCall("ask_user_question", Map.of(
                "questions", List.of(
                        Map.of(
                                "question", "UI?",
                                "header", "ui",
                                "options", List.of(Map.of("label", "React"), Map.of("label", "Vue"))
                        ),
                        Map.of(
                                "question", "含哪些特性?",
                                "header", "features",
                                "options", List.of(
                                        Map.of("label", "auth"),
                                        Map.of("label", "search"),
                                        Map.of("label", "chat")),
                                "multiSelect", true
                        )
                )
        ));

        AtomicReference<ToolResult> result = new AtomicReference<>();
        CompletableFuture.runAsync(() -> {
            SessionContext.push("sid-aq-2");
            try { result.set(tool.execute(call)); } finally { SessionContext.pop(null); }
        });
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline && control.listPending("sid-aq-2").isEmpty()) {
            Thread.sleep(10);
        }
        ClarifyQuestion cq = (ClarifyQuestion) control.listPending("sid-aq-2").get(0);
        assertEquals(2, cq.questions().size());
        assertTrue(cq.questions().get(1).multiSelect());

        control.answer("sid-aq-2", cq.askId(),
                new ChoiceAnswer(Map.of(
                        "0", List.of("React"),
                        "1", List.of("auth", "chat"))));

        Thread.sleep(500);
        ToolResult r = result.get();
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("\"React\""));
        assertTrue(r.getOutput().contains("\"auth\""));
        assertTrue(r.getOutput().contains("\"chat\""));
    }

    @Test
    @DisplayName("无 SessionContext:直接返 Error(不挂起,不占位)")
    void no_session_context_returns_error() {
        AgentControl control = new DefaultAgentControl();
        AskUserQuestionTool tool = new AskUserQuestionTool(control, Duration.ofSeconds(1));
        // 不 push SessionContext
        ToolResult r = tool.execute(sampleCall());
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().toLowerCase().contains("no session"));
    }

    @Test
    @DisplayName("参数校验:questions 空 / options < 2 / header 超长 → Error")
    void invalid_params_returns_error() {
        AgentControl control = new DefaultAgentControl();
        AskUserQuestionTool tool = new AskUserQuestionTool(control, Duration.ofSeconds(1));
        SessionContext.push("sid-aq-invalid");

        // 缺 questions
        ToolResult r1 = tool.execute(new ToolCall("ask_user_question", Map.of()));
        assertFalse(r1.isSuccess());

        // questions 空数组
        ToolResult r2 = tool.execute(new ToolCall("ask_user_question",
                Map.of("questions", List.of())));
        assertFalse(r2.isSuccess());

        // options 只有 1 个
        ToolResult r3 = tool.execute(new ToolCall("ask_user_question", Map.of(
                "questions", List.of(Map.of(
                        "question", "q?", "header", "h",
                        "options", List.of(Map.of("label", "only"))
                ))
        )));
        assertFalse(r3.isSuccess());

        // header 超 12 字
        ToolResult r4 = tool.execute(new ToolCall("ask_user_question", Map.of(
                "questions", List.of(Map.of(
                        "question", "q?", "header", "this-is-too-long",
                        "options", List.of(Map.of("label", "a"), Map.of("label", "b"))
                ))
        )));
        assertFalse(r4.isSuccess());
    }

    @Test
    @DisplayName("Timeout:短 timeout 未 answer → 返 timeout 提示,不抛异常")
    void timeout_returns_hint() {
        AgentControl control = new DefaultAgentControl();
        AskUserQuestionTool tool = new AskUserQuestionTool(control, Duration.ofMillis(200));
        SessionContext.push("sid-aq-timeout");

        ToolResult r = tool.execute(sampleCall());
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().toLowerCase().contains("did not respond"),
                "应含 timeout 提示,实际:" + r.getOutput());
    }

    @Test
    @DisplayName("summary 摘要:单问题显示 header,多问题显示计数")
    void summary_format() {
        AgentControl control = new DefaultAgentControl();
        AskUserQuestionTool tool = new AskUserQuestionTool(control, Duration.ofSeconds(1));

        assertEquals("❓ UI lib", tool.summary(sampleCall()));

        ToolCall multi = new ToolCall("ask_user_question", Map.of(
                "questions", List.of(
                        Map.of("question", "a?", "header", "A", "options", List.of()),
                        Map.of("question", "b?", "header", "B", "options", List.of())
                )
        ));
        assertEquals("❓ ask user (2)", tool.summary(multi));

        assertEquals("❓ ask user", tool.summary(null));
    }
}
