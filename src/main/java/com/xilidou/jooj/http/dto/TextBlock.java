package com.xilidou.jooj.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 普通文本块。assistant 在思考过程中输出的纯文本。
 *
 * <p>JSON: {@code {"type": "text", "text": "I'll run ls"}}
 *
 * <p>注意：{@code type} 字段不在这里写，由 {@link ContentBlock} 上的
 * {@code @JsonTypeInfo} 自动写入/读取。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextBlock implements ContentBlock {

    String text;

    @Override
    public String getType() {
        return "text";
    }
}
