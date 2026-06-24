package com.xilidou.marvis.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Extended Thinking 块（Claude Sonnet 4.x+ / Opus 等支持 reasoning 的模型）。
 *
 * <p>对应 JSON：
 * <pre>
 *   {
 *     "type": "thinking",
 *     "thinking": "Let me figure out the structure first...",
 *     "signature": "..."
 *   }
 * </pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>Loop 派发时**不需要处理**这种块（不是 tool_use 也不是 text）</li>
 *   <li>但**必须包含**在 messages 回传里（坑 4：assistant content 必须原样回传）</li>
 *   <li>{@code signature} 用于校验，必须保留原值</li>
 * </ul>
 *
 * <p>参考：<a href="https://docs.anthropic.com/en/docs/build-with-claude/extended-thinking">
 * Anthropic Extended Thinking 文档</a>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ThinkingBlock implements ContentBlock {

    private String thinking;     // 模型的 reasoning 过程

    private String signature;    // 校验签名，回传时必须原样保留

    @Override
    public String getType() {
        return "thinking";
    }
}
