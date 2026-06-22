package com.xilidou.marvis.harness.tool;

import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;

import java.util.List;

public interface Tool {
    String getName();
    String getDescription();
    List<ToolDefinition> getTools();
    ToolResult execute(ToolCall call);
}
