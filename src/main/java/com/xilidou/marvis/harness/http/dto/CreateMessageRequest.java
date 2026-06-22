package com.xilidou.marvis.harness.http.dto;

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

    private String system;

    private List<MessageParam> messages;

    private List<ToolDef> tools;

    private Double temperature;

    @JsonProperty("stop_sequences")
    private List<String> stopSequences;

}
