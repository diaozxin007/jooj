package com.xilidou.jooj.todo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Todo 三态。
 *
 * <p>对应 Python s05 的字符串 {@code "pending"} / {@code "in_progress"} / {@code "completed"}。
 *
 * <p>用 enum 而非字符串做内部表示，但**对外 JSON 序列化**仍是 snake_case 字符串
 * （由 {@link #value} + {@link #from} 控制）—— 与 LLM 通信用 LLM 友好的字符串，
 * Java 内部用类型安全的 enum，两端各取所长。
 */
public enum TodoStatus {

    /** 待办 */
    PENDING("pending"),

    /** 进行中（同一时间应只有一个 in_progress） */
    IN_PROGRESS("in_progress"),

    /** 已完成 */
    COMPLETED("completed");

    private final String value;

    TodoStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TodoStatus from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("status is null");
        }
        for (TodoStatus s : values()) {
            if (s.value.equals(value)) return s;
        }
        throw new IllegalArgumentException(
                "Invalid todo status: '" + value + "'. Expected: pending / in_progress / completed");
    }
}
