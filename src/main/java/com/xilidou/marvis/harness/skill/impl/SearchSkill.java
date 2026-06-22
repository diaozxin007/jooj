package com.xilidou.marvis.harness.skill.impl;

import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.http.dto.InputSchema;
import com.xilidou.marvis.harness.skill.Skill;

import java.util.List;
import java.util.Map;

/**
 * SearchSkill - 网页搜索 + 内容提取。
 *
 * <p>⚠️ 当前是 mock，Week 5 RAG 实操时改为真实 Tavily / Serper 调用。
 */
public class SearchSkill implements Skill {

    @Override
    public String getName() {
        return "search";
    }

    @Override
    public String getDescription() {
        return "Search the web and extract page content.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        return List.of(
                new ToolDefinition(
                        "web_search",
                        "Search the web for information",
                        InputSchema.object(
                                Map.of(
                                        "query",       Map.of("type", "string",  "description", "Search query"),
                                        "max_results", Map.of("type", "integer", "description", "Max results, default 10")
                                ),
                                "query"
                        )
                ),
                new ToolDefinition(
                        "extract",
                        "Fetch full content from a URL",
                        InputSchema.object(
                                Map.of("url", Map.of("type", "string", "description", "URL to fetch")),
                                "url"
                        )
                )
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if ("web_search".equals(call.getToolName())) {
            String query = (String) call.getArguments().get("query");
            return new ToolResult(true, String.format("[SEARCH] Results for '%s': [Result A, Result B, Result C]", query));
        }
        if ("extract".equals(call.getToolName())) {
            String url = (String) call.getArguments().get("url");
            return new ToolResult(true, String.format("[EXTRACT] Fetched content from %s (2500 words)", url));
        }
        return new ToolResult(false, "Unknown tool: " + call.getToolName());
    }
}
