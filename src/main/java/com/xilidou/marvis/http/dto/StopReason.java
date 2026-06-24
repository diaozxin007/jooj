package com.xilidou.marvis.http.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum StopReason {
    END_TURN("end_turn"),                 // 模型自己说完了
    TOOL_USE("tool_use"),                 // 模型要调工具
    MAX_TOKENS("max_tokens"),             // 被 max_tokens 截断
    STOP_SEQUENCE("stop_sequence"),       // 命中 stop sequence
    UNKNOWN("unknown");                   // 兜底，未来 Anthropic 加新值

    private final String value;
    StopReason(String value) { this.value = value; }

    @JsonValue
    public String value() { return value; }

    @JsonCreator
    public static StopReason from(String value) {
        if (value == null) return UNKNOWN;
        for (StopReason r : values()) {
            if (r.value.equals(value)) return r;
        }
        return UNKNOWN;   // ⚠️ 不要 throw，新字段不能让反序列化炸
    }
}
