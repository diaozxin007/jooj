package com.xilidou.jooj.todo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 TodoStore 的核心行为：整体替换语义 + 不可变快照 + 状态计数。
 */
class TodoStoreTest {

    @Test
    void empty_initially() {
        TodoStore store = new TodoStore();
        assertEquals(0, store.size());
        assertTrue(store.isEmpty());
        assertTrue(store.snapshot().isEmpty());
    }

    @Test
    void replace_overwrites_completely() {
        TodoStore store = new TodoStore();
        store.replace(List.of(
                new TodoItem("step 1", TodoStatus.PENDING),
                new TodoItem("step 2", TodoStatus.PENDING)
        ));
        assertEquals(2, store.size());

        // 整体替换：旧的 2 个被丢弃
        store.replace(List.of(new TodoItem("brand new", TodoStatus.IN_PROGRESS)));
        assertEquals(1, store.size());
        assertEquals("brand new", store.snapshot().get(0).getContent());
    }

    @Test
    void snapshot_is_immutable() {
        TodoStore store = new TodoStore();
        store.replace(List.of(new TodoItem("x", TodoStatus.PENDING)));

        List<TodoItem> snap = store.snapshot();
        assertThrows(UnsupportedOperationException.class,
                () -> snap.add(new TodoItem("hack", TodoStatus.PENDING)),
                "外部不应能修改 snapshot");
    }

    @Test
    void countByStatus() {
        TodoStore store = new TodoStore();
        store.replace(List.of(
                new TodoItem("a", TodoStatus.PENDING),
                new TodoItem("b", TodoStatus.IN_PROGRESS),
                new TodoItem("c", TodoStatus.COMPLETED),
                new TodoItem("d", TodoStatus.COMPLETED)
        ));
        assertEquals(1, store.countByStatus(TodoStatus.PENDING));
        assertEquals(1, store.countByStatus(TodoStatus.IN_PROGRESS));
        assertEquals(2, store.countByStatus(TodoStatus.COMPLETED));
    }

    @Test
    void replace_with_null_clears() {
        TodoStore store = new TodoStore();
        store.replace(List.of(new TodoItem("x", TodoStatus.PENDING)));
        store.replace(null);
        assertEquals(0, store.size());
    }

    @Test
    void clear() {
        TodoStore store = new TodoStore();
        store.replace(List.of(new TodoItem("x", TodoStatus.PENDING)));
        store.clear();
        assertTrue(store.isEmpty());
    }
}
