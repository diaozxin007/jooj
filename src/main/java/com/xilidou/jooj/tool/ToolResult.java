package com.xilidou.jooj.tool;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 工具执行结果。
 *
 * <p>R2 重构(2026-06-24):从 {@code harness.entity} 搬到 {@code harness.tool}。
 */
@Data
@AllArgsConstructor
public class ToolResult {
    private boolean success;
    private String output;
}
