package com.xilidou.marvis.harness.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ToolResult {
    private boolean success;
    private String output;
}
