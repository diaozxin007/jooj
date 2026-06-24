package com.xilidou.marvis.tool;

import com.xilidou.marvis.tool.ToolCall;
import com.xilidou.marvis.tool.ToolDefinition;
import com.xilidou.marvis.tool.ToolResult;

import java.util.List;

public interface Tool {
    String getName();
    String getDescription();
    List<ToolDefinition> getTools();
    ToolResult execute(ToolCall call);
}
