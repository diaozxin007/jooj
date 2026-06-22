package com.xilidou.marvis.harness.skill;

import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;

import java.util.List;

public interface Skill {
    String getName();
    String getDescription();
    List<ToolDefinition> getTools();
    ToolResult execute(ToolCall call);
}
