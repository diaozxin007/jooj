package com.xilidou.jooj.web;

import com.xilidou.jooj.agent.PendingQuestionRegistered;
import com.xilidou.jooj.agent.TurnEvent;
import com.xilidou.jooj.agent.TurnEventPushed;
import com.xilidou.jooj.agent.control.ClarifyQuestion;
import com.xilidou.jooj.agent.control.PermissionQuestion;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * s22 SSE:{@link SseStreamService} 单测。
 *
 * <ol>
 *   <li>register 首次连接 → hasConnection=true</li>
 *   <li>register 同 sid 二次 → 踢老 emitter,activeCount 不叠加</li>
 *   <li>close 主动断开</li>
 *   <li>null / blank 防御</li>
 *   <li>onTurnEvent 监听:agent 发 event → push 走 tool_start</li>
 *   <li>onPendingQuestion:permission / clarify 两种子类型都序列化对</li>
 * </ol>
 */
class SseStreamServiceTest {

    @Test
    @DisplayName("register:首次连接 → hasConnection true,activeCount=1")
    void register_new_connection() {
        var svc = new SseStreamService();
        assertFalse(svc.hasConnection("sid1"));
        var emitter = svc.register("sid1");
        assertNotNull(emitter);
        assertTrue(svc.hasConnection("sid1"));
        assertEquals(1, svc.activeCount());
        svc.close("sid1");
    }

    @Test
    @DisplayName("register 同 sid 二次:踢老 emitter,activeCount 保持 1")
    void register_replaces_existing() {
        var svc = new SseStreamService();
        svc.register("sid1");
        svc.register("sid1");   // 二次注册
        assertEquals(1, svc.activeCount());
        svc.close("sid1");
    }

    @Test
    @DisplayName("close:hasConnection 转 false")
    void close_removes_connection() {
        var svc = new SseStreamService();
        svc.register("sid1");
        svc.close("sid1");
        assertFalse(svc.hasConnection("sid1"));
        assertEquals(0, svc.activeCount());
    }

    @Test
    @DisplayName("null / blank sid 防御")
    void null_or_blank_defensive() {
        var svc = new SseStreamService();
        assertThrows(IllegalArgumentException.class, () -> svc.register(null));
        assertThrows(IllegalArgumentException.class, () -> svc.register(""));
        assertFalse(svc.hasConnection(null));
        svc.close(null);   // no-op,不抛
        svc.push(null, "any", "1", "{}");   // no-op
        svc.push("sid", "any", "1", "{}");  // 无 emitter,也是 no-op
    }

    @Test
    @DisplayName("onTurnEvent:发 push 时不抛,无活跃连接时 no-op")
    void on_turn_event_no_op_without_connection() {
        var svc = new SseStreamService();
        // 无连接直接 push,不抛异常
        svc.onTurnEvent(new TurnEventPushed("nonexistent",
                new TurnEvent(1, Instant.now(), "tool_start", "$ ls")));
    }

    @Test
    @DisplayName("onPendingQuestion permission:不抛,允许 null summary 里的 reason")
    void on_pending_permission() {
        var svc = new SseStreamService();
        var toolUse = new ToolUseBlock("toolu_1", "bash",
                JsonNodeFactory.instance.objectNode().put("command", "ls"));
        var pq = PermissionQuestion.of(toolUse, "dangerous cmd");
        svc.present("nonexistent", pq);
    }

    @Test
    @DisplayName("onPendingQuestion clarify:多 option 里 description null 的也能序列化")
    void on_pending_clarify() {
        var svc = new SseStreamService();
        var cq = ClarifyQuestion.of(List.of(
                new ClarifyQuestion.SubQuestion("q?", "hdr",
                        List.of(
                                new ClarifyQuestion.Option("A", "with desc"),
                                new ClarifyQuestion.Option("B", null)),
                        false)));
        svc.present("nonexistent", cq);
    }
}
