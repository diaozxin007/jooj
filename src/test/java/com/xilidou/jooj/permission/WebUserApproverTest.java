package com.xilidou.jooj.permission;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xilidou.jooj.agent.AgentControl;
import com.xilidou.jooj.agent.AgentInterruptedException;
import com.xilidou.jooj.agent.DefaultAgentControl;
import com.xilidou.jooj.agent.SessionContext;
import com.xilidou.jooj.agent.control.AllowAnswer;
import com.xilidou.jooj.agent.control.Answer;
import com.xilidou.jooj.agent.control.AskTimeoutException;
import com.xilidou.jooj.agent.control.DenyAnswer;
import com.xilidou.jooj.agent.control.PendingQuestion;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * s22 D-10-C:{@link WebUserApprover} 单测。
 *
 * <h3>覆盖场景</h3>
 * <ol>
 *   <li>正常 ALLOW:approve 返 true</li>
 *   <li>用户 DENY:approve 返 false</li>
 *   <li>Timeout:approve 返 false(保守 deny)</li>
 *   <li>Interrupt 期间:approve 返 false(让 loop 自己检查点抛)</li>
 *   <li>无 sid 绑定:approve 返 false(保守 deny)</li>
 * </ol>
 */
class WebUserApproverTest {

    private ToolUseBlock sampleTool() {
        return new ToolUseBlock("toolu_test",
                "bash",
                JsonNodeFactory.instance.objectNode().put("command", "rm -rf build"));
    }

    @AfterEach
    void cleanup() {
        SessionContext.pop(null);
    }

    @Test
    @DisplayName("正常 ALLOW:approve 返 true,agent 线程被 AllowAnswer 唤醒")
    void approve_allow() throws Exception {
        AgentControl control = new DefaultAgentControl();
        WebUserApprover approver = new WebUserApprover(control, Duration.ofSeconds(5));

        SessionContext.push("sid-web-1");

        AtomicReference<Boolean> result = new AtomicReference<>();
        CompletableFuture<Void> agentThread = CompletableFuture.runAsync(() -> {
            // WebUserApprover 从 SessionContext.current() 读 sid,所以要在同线程 push
            SessionContext.push("sid-web-1");
            try {
                result.set(approver.approve(sampleTool(), "matched destructive pattern"));
            } finally {
                SessionContext.pop(null);
            }
        });

        // 等 ask 挂起
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline
                && control.listPending("sid-web-1").isEmpty()) {
            Thread.sleep(10);
        }
        assertEquals(1, control.listPending("sid-web-1").size());
        PendingQuestion q = control.listPending("sid-web-1").get(0);

        // REST 层 answer
        assertTrue(control.answer("sid-web-1", q.askId(), AllowAnswer.INSTANCE));

        agentThread.get(2, TimeUnit.SECONDS);
        assertTrue(result.get(), "ALLOW 应转成 true");
    }

    @Test
    @DisplayName("用户 DENY:approve 返 false")
    void approve_deny() throws Exception {
        AgentControl control = new DefaultAgentControl();
        WebUserApprover approver = new WebUserApprover(control, Duration.ofSeconds(5));

        AtomicReference<Boolean> result = new AtomicReference<>();
        CompletableFuture<Void> agentThread = CompletableFuture.runAsync(() -> {
            SessionContext.push("sid-web-2");
            try {
                result.set(approver.approve(sampleTool(), "rm dangerous"));
            } finally {
                SessionContext.pop(null);
            }
        });

        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline
                && control.listPending("sid-web-2").isEmpty()) {
            Thread.sleep(10);
        }
        PendingQuestion q = control.listPending("sid-web-2").get(0);

        assertTrue(control.answer("sid-web-2", q.askId(), new DenyAnswer("nope")));

        agentThread.get(2, TimeUnit.SECONDS);
        assertFalse(result.get(), "DENY 应转成 false");
    }

    @Test
    @DisplayName("超时:approve 返 false(保守 deny)")
    void approve_timeout() {
        AgentControl control = new DefaultAgentControl();
        WebUserApprover approver = new WebUserApprover(control, Duration.ofMillis(200));

        SessionContext.push("sid-web-3");
        long start = System.currentTimeMillis();
        boolean result = approver.approve(sampleTool(), "test timeout");
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result, "timeout 保守 deny");
        assertTrue(elapsed >= 200 && elapsed < 1500,
                "应耗时 ~200ms(timeout),实际 " + elapsed);
    }

    @Test
    @DisplayName("Interrupt 期间:approve 返 false(不重抛 AgentInterruptedException,让 loop 检查点抛)")
    void approve_interrupted() throws Exception {
        AgentControl control = new DefaultAgentControl();
        WebUserApprover approver = new WebUserApprover(control, Duration.ofSeconds(30));

        AtomicReference<Boolean> result = new AtomicReference<>();
        AtomicReference<Throwable> caught = new AtomicReference<>();
        CompletableFuture<Void> agentThread = CompletableFuture.runAsync(() -> {
            SessionContext.push("sid-web-4");
            try {
                result.set(approver.approve(sampleTool(), "test interrupt"));
            } catch (Throwable t) {
                caught.set(t);
            } finally {
                SessionContext.pop(null);
            }
        });

        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline
                && control.listPending("sid-web-4").isEmpty()) {
            Thread.sleep(10);
        }

        // 触发 interrupt → cancelPending → agent 线程收到 AgentInterruptedException
        // WebUserApprover 内部 catch 并转成 false(不重抛)
        control.requestInterrupt("sid-web-4");

        agentThread.get(2, TimeUnit.SECONDS);
        assertNull(caught.get(), "WebUserApprover 内部消化 interrupt,不重抛");
        assertFalse(result.get(), "interrupt 期间 → DENY");
    }

    @Test
    @DisplayName("无 sid 绑定:approve 返 false(降级保守 deny)")
    void approve_no_sid_context() {
        AgentControl control = new DefaultAgentControl();
        WebUserApprover approver = new WebUserApprover(control, Duration.ofSeconds(1));

        // 不调 SessionContext.push
        assertNull(SessionContext.current());
        boolean result = approver.approve(sampleTool(), "test no context");
        assertFalse(result, "无 sid 应保守 deny");
    }
}
