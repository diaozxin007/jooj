package com.xilidou.marvis.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.config.JacksonConfig;
import com.xilidou.marvis.http.dto.ContentBlock;
import com.xilidou.marvis.http.dto.CreateMessageResponse;
import com.xilidou.marvis.http.dto.TextBlock;
import com.xilidou.marvis.http.dto.ThinkingBlock;
import com.xilidou.marvis.http.dto.ToolUseBlock;
import com.xilidou.marvis.http.dto.Usage;

import java.util.List;
import java.util.Map;

/**
 * 测试用 Response 构造器。让 mock 响应一行写完，不用每次手搓。
 *
 * <p>用法：
 * <pre>
 *   CreateMessageResponse resp = ResponseFixtures.toolUse("bash", "ls -la");
 *   CreateMessageResponse done = ResponseFixtures.endTurn("Found 20 files");
 * </pre>
 */
public final class ResponseFixtures {

    private static final ObjectMapper JSON = JacksonConfig.newMapper();

    private ResponseFixtures() {}

    /**
     * 构造一个 {@code stop_reason: "end_turn"} 响应（含一个 text block）。
     * 模拟"模型说完了，loop 应该退出"的场景。
     */
    public static CreateMessageResponse endTurn(String text) {
        return baseResponse(List.of(new TextBlock(text)), "end_turn");
    }

    /**
     * 构造一个 {@code stop_reason: "tool_use"} 响应（含一个 tool_use block）。
     * 模拟"模型决定调工具"的场景。
     */
    public static CreateMessageResponse toolUse(String toolName, Map<String, Object> input) {
        return toolUse(toolName, input, "toolu_test_" + System.identityHashCode(input));
    }

    /**
     * 构造 tool_use 响应（指定 tool_use_id，方便后续断言）。
     */
    public static CreateMessageResponse toolUse(String toolName, Map<String, Object> input,
                                                 String toolUseId) {
        JsonNode inputNode = JSON.valueToTree(input);
        return baseResponse(
                List.of(new ToolUseBlock(toolUseId, toolName, inputNode)),
                "tool_use"
        );
    }

    /**
     * 构造一个混合 thinking + tool_use 响应（模拟 Claude Sonnet 4.6 的行为）。
     *
     * <p>这是真实场景，用来验证 thinking block 不会破坏 loop。
     */
    public static CreateMessageResponse thinkingPlusToolUse(String thinking, String signature,
                                                             String toolName,
                                                             Map<String, Object> input,
                                                             String toolUseId) {
        JsonNode inputNode = JSON.valueToTree(input);
        return baseResponse(
                List.of(
                        new ThinkingBlock(thinking, signature),
                        new ToolUseBlock(toolUseId, toolName, inputNode)
                ),
                "tool_use"
        );
    }

    /**
     * 构造一个含多个 tool_use 的响应（一轮里调多个工具）。
     */
    public static CreateMessageResponse multipleToolUse(List<ToolUseBlock> toolUses) {
        return baseResponse(List.copyOf(toolUses), "tool_use");
    }

    /**
     * 构造一个 ToolUseBlock（提取出来方便 multipleToolUse 用）。
     */
    public static ToolUseBlock makeToolUse(String name, Map<String, Object> input, String id) {
        JsonNode inputNode = JSON.valueToTree(input);
        return new ToolUseBlock(id, name, inputNode);
    }

    // ── 内部：构造响应骨架 ──────────────────────────────────────
    private static CreateMessageResponse baseResponse(List<ContentBlock> content, String stopReason) {
        Usage usage = new Usage(100, 50, null, null);
        return new CreateMessageResponse(
                "msg_test_" + System.identityHashCode(content),
                "message",
                "assistant",
                content,
                "claude-test-model",
                stopReason,
                null,
                usage
        );
    }
}
