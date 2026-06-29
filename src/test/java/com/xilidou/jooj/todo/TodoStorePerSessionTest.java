package com.xilidou.jooj.todo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * s20 Demo 12 回归 —— 锁定 TodoStore 跨 session 不串味。
 */
class TodoStorePerSessionTest {

    @Test
    @DisplayName("两个 session 的 todo list 完全独立")
    void per_session_isolation() {
        TodoStore store = new TodoStore();
        store.replace("alice", List.of(
                new TodoItem("alice task 1", TodoStatus.PENDING),
                new TodoItem("alice task 2", TodoStatus.IN_PROGRESS)));
        store.replace("bob", List.of(
                new TodoItem("bob task", TodoStatus.PENDING)));

        assertEquals(2, store.size("alice"));
        assertEquals(1, store.size("bob"));
        assertEquals("alice task 1", store.snapshot("alice").get(0).getContent());
        assertEquals("bob task", store.snapshot("bob").get(0).getContent());
    }

    @Test
    @DisplayName("一个 session 的 clear 不影响别的 session")
    void clear_does_not_cross_sessions() {
        TodoStore store = new TodoStore();
        store.replace("alice", List.of(new TodoItem("a", TodoStatus.PENDING)));
        store.replace("bob", List.of(new TodoItem("b", TodoStatus.PENDING)));

        store.clear("alice");

        assertTrue(store.isEmpty("alice"));
        assertEquals(1, store.size("bob"), "clear alice 不该影响 bob");
    }

    @Test
    @DisplayName("countByStatus 按 session 分别计数")
    void countByStatus_per_session() {
        TodoStore store = new TodoStore();
        store.replace("alice", List.of(
                new TodoItem("a1", TodoStatus.PENDING),
                new TodoItem("a2", TodoStatus.IN_PROGRESS)));
        store.replace("bob", List.of(
                new TodoItem("b1", TodoStatus.PENDING),
                new TodoItem("b2", TodoStatus.PENDING),
                new TodoItem("b3", TodoStatus.IN_PROGRESS)));

        assertEquals(1, store.countByStatus("alice", TodoStatus.PENDING));
        assertEquals(2, store.countByStatus("bob", TodoStatus.PENDING));
        assertEquals(1, store.countByStatus("alice", TodoStatus.IN_PROGRESS));
        assertEquals(1, store.countByStatus("bob", TodoStatus.IN_PROGRESS));
    }

    @Test
    @DisplayName("null/blank sessionId 都路由到 DEFAULT_SESSION,跟老 API 一致")
    void null_sessionId_falls_back_to_default() {
        TodoStore store = new TodoStore();
        store.replace((String) null, List.of(new TodoItem("x", TodoStatus.PENDING)));
        assertEquals(1, store.size(TodoStore.DEFAULT_SESSION));
        assertEquals(1, store.size());   // 老无参 API 等价

        store.replace("", List.of(new TodoItem("y", TodoStatus.PENDING)));
        // 替换走的是同一 DEFAULT 分区,size 仍 1(覆盖,不是追加)
        assertEquals(1, store.size());
    }
}
