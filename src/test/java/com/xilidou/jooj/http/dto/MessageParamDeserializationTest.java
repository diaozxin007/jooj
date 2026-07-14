package com.xilidou.jooj.http.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link MessageParam#content} 的反序列化 —— 两种形态都要走对派发,不能把数组
 * 降级成 LinkedHashMap。这直接决定 {@link com.xilidou.jooj.session.HistoryScrubber}
 * 能不能识别磁盘上残留的孤儿 tool_use / tool_result。
 */
class MessageParamDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void should_keep_string_content_as_string() throws Exception {
        String json = """
                {"role": "user", "content": "你好"}
                """;
        MessageParam m = mapper.readValue(json, MessageParam.class);

        assertEquals("user", m.getRole());
        assertInstanceOf(String.class, m.getContent());
        assertEquals("你好", m.getContent());
    }

    @Test
    void should_deserialize_array_content_into_content_blocks() throws Exception {
        // 模拟磁盘 session JSON 里 assistant 消息带 tool_use 的形态。
        String json = """
                {
                  "role": "assistant",
                  "content": [
                    {"type": "text", "text": "让我先看看目录"},
                    {"type": "tool_use", "id": "toolu_abc", "name": "bash", "input": {"command": "ls"}}
                  ]
                }
                """;
        MessageParam m = mapper.readValue(json, MessageParam.class);

        assertInstanceOf(List.class, m.getContent());
        List<?> blocks = (List<?>) m.getContent();
        assertEquals(2, blocks.size());

        // 关键断言:element 必须是具体的 ContentBlock 子类,不能是 LinkedHashMap。
        assertInstanceOf(TextBlock.class, blocks.get(0));
        assertInstanceOf(ToolUseBlock.class, blocks.get(1));

        ToolUseBlock tu = (ToolUseBlock) blocks.get(1);
        assertEquals("toolu_abc", tu.getId());
        assertEquals("bash", tu.getName());
    }

    @Test
    void should_deserialize_tool_result_array_correctly() throws Exception {
        String json = """
                {
                  "role": "user",
                  "content": [
                    {"type": "tool_result", "tool_use_id": "toolu_abc", "content": "result-text"}
                  ]
                }
                """;
        MessageParam m = mapper.readValue(json, MessageParam.class);

        List<?> blocks = (List<?>) m.getContent();
        assertInstanceOf(ToolResultBlock.class, blocks.get(0));
        assertEquals("toolu_abc", ((ToolResultBlock) blocks.get(0)).getToolUseId());
    }

    /**
     * 回归防御:模拟磁盘 default.json 上真实出现过的孤儿场景 —— assistant.tool_use(id=X)
     * 后接 user 字符串 "[snipped ...]",没有配对 tool_result。反序列化后
     * {@link com.xilidou.jooj.session.HistoryScrubber} 才能通过 {@code instanceof}
     * 判定识别并剔除孤儿。这里只验证反序列化侧结果,scrub 行为由 HistoryScrubberTest 覆盖。
     */
    @Test
    void should_expose_orphan_tool_use_via_instanceof_after_disk_roundtrip() throws Exception {
        String json = """
                [
                  {"role": "user", "content": "你好"},
                  {"role": "assistant", "content": [
                      {"type": "tool_use", "id": "toolu_orphan", "name": "bash", "input": {}}
                  ]},
                  {"role": "user", "content": "[snipped 5 messages]"}
                ]
                """;
        List<MessageParam> history = mapper.readValue(
                json,
                mapper.getTypeFactory().constructCollectionType(List.class, MessageParam.class)
        );

        assertEquals(3, history.size());
        Object assistantContent = history.get(1).getContent();
        assertInstanceOf(List.class, assistantContent);

        List<?> blocks = (List<?>) assistantContent;
        // 反序列化必须给出 ToolUseBlock 具体类型,否则 HistoryScrubber 会漏判。
        boolean sawToolUse = false;
        for (Object b : blocks) {
            if (b instanceof ToolUseBlock tu) {
                sawToolUse = true;
                assertEquals("toolu_orphan", tu.getId());
            }
        }
        assertTrue(sawToolUse, "assistant.content 里必须能通过 instanceof 识别 ToolUseBlock");

        // 兜底:第 3 条消息 content 是 String,不能被误当数组。
        assertInstanceOf(String.class, history.get(2).getContent());
        assertFalse(history.get(2).getContent() instanceof List<?>);
    }
}