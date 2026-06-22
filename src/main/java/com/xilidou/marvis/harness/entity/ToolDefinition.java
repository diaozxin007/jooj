package com.xilidou.marvis.harness.entity;

import com.xilidou.marvis.harness.http.dto.InputSchema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Skill 层的工具定义（业务抽象）。
 *
 * <p>对比 {@link com.xilidou.marvis.harness.http.dto.ToolDef}（HTTP 协议层 DTO）：
 * <ul>
 *   <li>这个类是 Skill 接口的返回类型，**不绑定任何 LLM 厂商协议**</li>
 *   <li>ToolDef 是 Anthropic 协议 DTO，序列化时直接进 HTTP body</li>
 *   <li>{@code AgentLoopHarness.buildTools()} 做适配：ToolDefinition → ToolDef</li>
 * </ul>
 *
 * <p>这种分层让 Skill 实现不需要了解 Anthropic 协议——将来切 OpenAI / 国产模型，
 * 只需改 buildTools 的适配，所有 Skill 不动。
 *
 * <p>关于 {@link InputSchema}：虽然来自 http.dto 包，但 InputSchema 描述的是
 * **通用 JSON Schema（type=object + properties + required）**，不是 Anthropic 私有格式。
 * OpenAI / 国产模型也用同样格式定义工具入参。所以这里复用是合理的。
 */
@Data
@AllArgsConstructor
public class ToolDefinition {

    private String name;

    private String description;

    private InputSchema inputSchema;

    @Override
    public String toString() {
        return String.format("Tool[%s: %s]", name, description);
    }
}
