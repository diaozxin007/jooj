package com.xilidou.jooj.todo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条待办项。
 *
 * <p>对应 Python s05 的 {@code {"content": "...", "status": "pending"}}。
 *
 * <p>Jackson 反序列化兼容：
 * <ul>
 *   <li>{@link TodoStatus} 通过 {@code @JsonCreator} 把字符串转 enum</li>
 *   <li>{@code @JsonIgnoreProperties} 兼容未来加字段（如 priority / id）</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TodoItem {

    /** 任务描述 */
    private String content;

    /** 任务状态 */
    private TodoStatus status;

    public boolean isCompleted() {
        return status == TodoStatus.COMPLETED;
    }
}
