package com.xilidou.marvis.harness.base;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class ToolCall {

    private String toolName;
    private Map<String, Object> arguments;

}
