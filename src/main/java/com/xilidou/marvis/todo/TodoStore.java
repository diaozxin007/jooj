package com.xilidou.marvis.todo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 当前 session 的待办列表存储。
 *
 * <p>对应 Python s05 的全局变量 {@code CURRENT_TODOS}：
 * <pre>
 *   CURRENT_TODOS: list[dict] = []
 * </pre>
 *
 * <p>Java 化设计：
 * <ul>
 *   <li>包成 {@code @Component} 让 Spring 单例管理（替代全局静态变量）</li>
 *   <li>方法 {@code synchronized} 保证 Week 8 后台任务并发安全</li>
 *   <li>{@link #replace} 整体替换语义对应 Python "todos = new_todos" 赋值</li>
 *   <li>{@link #snapshot} 返回不可变副本，避免外部修改污染</li>
 * </ul>
 *
 * <p>**为什么不用 ConcurrentHashMap**？因为这里的语义是"整个列表替换"
 * （LLM 每次 todo_write 给的是完整 todo list），不是"按 id 单条更新"。
 * synchronized + List 比 ConcurrentList 表达更准确。
 */
@Component
@Slf4j
public class TodoStore {

    private List<TodoItem> todos = new ArrayList<>();

    /**
     * 整体替换 todo list（对应 Python 的赋值语义）。
     */
    public synchronized void replace(List<TodoItem> newTodos) {
        if (newTodos == null) {
            this.todos = new ArrayList<>();
        } else {
            this.todos = new ArrayList<>(newTodos);
        }
        log.debug("[TodoStore] replaced with {} items", this.todos.size());
    }

    /**
     * 获取不可变快照——外部不能改。
     */
    public synchronized List<TodoItem> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(todos));
    }

    public synchronized int size() {
        return todos.size();
    }

    public synchronized boolean isEmpty() {
        return todos.isEmpty();
    }

    public synchronized long countByStatus(TodoStatus status) {
        return todos.stream().filter(t -> t.getStatus() == status).count();
    }

    public synchronized void clear() {
        todos.clear();
    }
}
