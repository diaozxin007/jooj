package com.xilidou.jooj.http.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.List;

/**
 * {@link MessageParam#content} 的反序列化器。
 *
 * <p>背景:{@code MessageParam.content} 声明为 {@code Object},因为 Anthropic 协议里
 * 它可以是两种形态之一 ——
 * <ul>
 *   <li>裸字符串:{@code "content": "Hello"}(用户首次输入)</li>
 *   <li>ContentBlock 数组:{@code "content": [{"type": "text", ...}, ...]}
 *       (assistant 输出 / user tool_result 回传)</li>
 * </ul>
 *
 * <p><b>不用自定义反序列化器时的坑</b>:Jackson 遇到 {@code Object} 字段,数组默认按
 * {@code ArrayList<LinkedHashMap>} 反序列化(丢掉 {@code ContentBlock} 上的
 * {@code @JsonTypeInfo} 派发)。session JSON 从盘读回来后,
 * {@link com.xilidou.jooj.session.HistoryScrubber#scrub} 里的
 * {@code instanceof ToolUseBlock} / {@code instanceof ToolResultBlock} 全部 miss,
 * 孤儿 tool_use ↔ tool_result 无法识别,发给 Anthropic 直接 400。
 *
 * <p><b>本实现</b>:按 token 类型分派 —— 字符串直接读 String,数组走
 * {@code TypeReference<List<ContentBlock>>} 让 Jackson 用 {@link ContentBlock}
 * 上已有的多态注解正常派发到具体子类。其他形态(null / 数字 / 对象)保守回退到默认 Object 语义,
 * 保留最大兼容性。
 *
 * <p>本类只影响读盘 / 反序列化路径,序列化侧无需改动 —— assistant / toolResults 工厂
 * 已经构造好正确的 {@code List<ContentBlock>}。
 */
public class MessageContentDeserializer extends JsonDeserializer<Object> {

    private static final TypeReference<List<ContentBlock>> BLOCK_LIST =
            new TypeReference<>() {
            };

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken t = p.currentToken();
        if (t == JsonToken.VALUE_STRING) {
            return p.getText();
        }
        if (t == JsonToken.START_ARRAY) {
            // 用 codec.readValue(...) —— 比 ctxt.readValue 更明确地走 ObjectMapper 主流程,
            // 保证 ContentBlock 上的 @JsonTypeInfo 派发被完整应用。
            return p.getCodec().readValue(p, ctxt.getTypeFactory().constructType(BLOCK_LIST));
        }
        // null / 数字 / 对象等其他形态:交给默认 Object 反序列化,保持向前兼容。
        return p.getCodec().readValue(p,Object.class);
    }
}