package com.xilidou.marvis.http.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 未知 type 的 content block 兜底容器。
 *
 * <p>当 Anthropic 协议演进、加了新的 block type（比如 image / document / mcp_tool_use 等）
 * 而我们还没来得及加对应的子类时，Jackson 会把该 block 反序列化为这个类，
 * 而不是抛出 {@code InvalidTypeIdException}。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code @JsonAnySetter}：把所有未知字段塞进 {@link #properties}</li>
 *   <li>{@code @JsonAnyGetter}：序列化时把 {@link #properties} 平铺出来</li>
 *   <li>这样**回传**时 block 内容**完全保真**（满足坑 4：assistant content 必须原样回传）</li>
 * </ul>
 *
 * <p>Loop 派发时，{@link UnknownBlock} 既不是 {@link TextBlock}（不打印）
 * 也不是 {@link ToolUseBlock}（不执行），自然被忽略 ✅
 *
 * <p>但它仍会被**完整保留在 messages 历史里**，确保下一轮请求合规。
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UnknownBlock implements ContentBlock {

    private Map<String, Object> properties = new HashMap<>();

    @JsonAnySetter
    public void setProperty(String key, Object value) {
        properties.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getProperties() {
        return properties;
    }

    @Override
    public String getType() {
        // type 字段会被 @JsonAnySetter 塞进 properties，从那里取
        Object t = properties.get("type");
        return t != null ? t.toString() : "unknown";
    }
}
