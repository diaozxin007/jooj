package com.xilidou.marvis.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Task 三态。
 *
 * <p>对应 Python s12 的字符串 {@code "pending" / "in_progress" / "completed"}。
 *
 * <p>跟 {@link com.xilidou.marvis.todo.TodoStatus} 同模式:enum 给 Java 内部类型安全,
 * 序列化用 {@code @JsonValue} + {@code @JsonCreator} 走 snake_case 字符串和 LLM / 文件
 * JSON 通信。
 *
 * <p><b>没有 BLOCKED / FAILED / CANCELLED</b>:严格对齐上游 ——
 * "blocked" 是计算属性({@link TaskService#canStart} 当场遍历 {@code blockedBy}
 * 决定能否 claim),不存储。
 */
public enum TaskStatus {

    /** 待办,尚未被任何 owner 认领 */
    PENDING("pending"),

    /** 已被认领,正在执行 */
    IN_PROGRESS("in_progress"),

    /** 已完成 */
    COMPLETED("completed");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TaskStatus from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("status is null");
        }
        for (TaskStatus s : values()) {
            if (s.value.equals(value)) return s;
        }
        throw new IllegalArgumentException(
                "Invalid task status: '" + value + "'. Expected: pending / in_progress / completed");
    }
}
