package com.xilidou.marvis.tool;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * 工具调用请求 — 工具名 + 参数 map。
 *
 * <p>由 {@code AgentLoopHarness} 在收到 LLM 的 tool_use block 后构造,
 * 派发给 {@link com.xilidou.marvis.tool.ToolRegistry#execute(ToolCall)}。
 *
 * <p>R2 重构(2026-06-24):从 {@code harness.base} 搬到 {@code harness.tool},
 * 让 Tool 相关的 3 个数据类型(Definition / Result / Call)同包。
 */
@Data
@AllArgsConstructor
public class ToolCall {

    private String toolName;
    private Map<String, Object> arguments;

}
