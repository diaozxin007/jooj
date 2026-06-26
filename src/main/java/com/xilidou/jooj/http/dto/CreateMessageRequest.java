package com.xilidou.jooj.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Anthropic Messages API 请求体。
 *
 * <p>对应 JSON：
 * <pre>
 *   {
 *     "model": "claude-sonnet-4-6",
 *     "max_tokens": 8000,
 *     "system": "...",
 *     "messages": [...],
 *     "tools": [...],
 *     "temperature": 0.7,
 *     "stop_sequences": [...]
 *   }
 * </pre>
 *
 * <p>用法：
 * <pre>
 *   CreateMessageRequest req = CreateMessageRequest.builder()
 *           .model("claude-sonnet-4-6")
 *           .maxTokens(8000)
 *           .system("You are helpful")
 *           .messages(List.of(MessageParam.user("Hi")))
 *           .build();
 * </pre>
 *
 * <p>关键注解说明：
 * <ul>
 *   <li>@Builder - Lombok 生成 builder() 链式 API</li>
 *   <li>@JsonInclude(NON_NULL) - 可选字段不序列化（system / temperature 等）</li>
 *   <li>@JsonProperty - 驼峰转蛇形（maxTokens → max_tokens）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateMessageRequest {

    private String model;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /**
     * SYSTEM prompt。Anthropic 协议允许两种形态:
     * <ul>
     *   <li><b>String</b>:简单场景,整段 SYSTEM 一次发完,不加缓存</li>
     *   <li><b>{@code List<SystemTextBlock>}</b>:可在 block 上标
     *       {@link com.xilidou.jooj.http.dto.SystemTextBlock} 的 cache_control 启用 prompt cache。
     *       拼接时按数组顺序送进 prompt</li>
     * </ul>
     *
     * <p>类型设为 {@link Object} 让 Jackson 透明序列化两种形态。设错类型
     * (例如 {@code List<String>})会被服务器拒绝。</p>
     */
    private Object system;

    /**
     * 测试 / 日志便利方法:把 system 字段抽成"完整文本"用于断言或观测。
     * <ul>
     *   <li>{@code String} 形态 → 原值</li>
     *   <li>{@code List<SystemTextBlock>} 形态 → 各 block 的 text 用 {@code "\n\n"} 拼接</li>
     *   <li>其它/null → 空字符串</li>
     * </ul>
     */
    public String getSystemText() {
        if (system == null) return "";
        if (system instanceof String s) return s;
        if (system instanceof java.util.List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object block : blocks) {
                if (block instanceof SystemTextBlock stb && stb.getText() != null) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(stb.getText());
                }
            }
            return sb.toString();
        }
        return system.toString();
    }

    private List<MessageParam> messages;

    private List<ToolDef> tools;

    private Double temperature;

    @JsonProperty("stop_sequences")
    private List<String> stopSequences;

}
