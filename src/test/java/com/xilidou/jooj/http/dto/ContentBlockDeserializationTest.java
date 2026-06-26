package com.xilidou.jooj.http.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JacksonConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ContentBlock 多态反序列化是否正确工作。
 *
 * 关键：3 种 type 必须正确派发到 TextBlock / ToolUseBlock / ToolResultBlock。
 */
class ContentBlockDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void should_deserialize_text_block() throws Exception {
        String json = """
                {"type": "text", "text": "Hello world"}
                """;
        ContentBlock block = mapper.readValue(json, ContentBlock.class);

        assertInstanceOf(TextBlock.class, block);
        assertEquals("Hello world", ((TextBlock) block).getText());
    }

    @Test
    void should_deserialize_tool_use_block() throws Exception {
        String json = """
                {
                    "type": "tool_use",
                    "id": "toolu_01XYZ",
                    "name": "bash",
                    "input": {"command": "ls -la"}
                }
                """;
        ContentBlock block = mapper.readValue(json, ContentBlock.class);

        assertInstanceOf(ToolUseBlock.class, block);
        ToolUseBlock tu = (ToolUseBlock) block;
        assertEquals("toolu_01XYZ", tu.getId());
        assertEquals("bash", tu.getName());
        assertEquals("ls -la", tu.getInput().get("command").asText());
    }

    @Test
    void should_deserialize_tool_result_block_with_snake_case() throws Exception {
        // 注意 JSON 是 snake_case "tool_use_id"，Java 字段是 toolUseId
        String json = """
                {
                    "type": "tool_result",
                    "tool_use_id": "toolu_01XYZ",
                    "content": "file1.txt\\nfile2.txt"
                }
                """;
        ContentBlock block = mapper.readValue(json, ContentBlock.class);

        assertInstanceOf(ToolResultBlock.class, block);
        ToolResultBlock tr = (ToolResultBlock) block;
        assertEquals("toolu_01XYZ", tr.getToolUseId());
        assertEquals("file1.txt\nfile2.txt", tr.getContent());
    }

    @Test
    void should_deserialize_array_of_mixed_blocks() throws Exception {
        // 这是真实场景：assistant 返回的 content 是混合数组
        String json = """
                [
                    {"type": "text", "text": "I'll list files"},
                    {"type": "tool_use", "id": "toolu_xxx",
                     "name": "bash", "input": {"command": "ls"}}
                ]
                """;
        List<ContentBlock> blocks = mapper.readValue(
                json,
                mapper.getTypeFactory().constructCollectionType(List.class, ContentBlock.class)
        );

        assertEquals(2, blocks.size());
        assertInstanceOf(TextBlock.class, blocks.get(0));
        assertInstanceOf(ToolUseBlock.class, blocks.get(1));
    }

    @Test
    void should_serialize_text_block_with_type_field() throws Exception {
        TextBlock block = new TextBlock("Hello");
        String json = mapper.writeValueAsString(block);

        // 序列化时 type 字段必须自动加上
        assertTrue(json.contains("\"type\":\"text\""));
        assertTrue(json.contains("\"text\":\"Hello\""));
    }

    @Test
    void should_serialize_tool_result_with_snake_case() throws Exception {
        ToolResultBlock block = ToolResultBlock.ofText("toolu_xxx", "output");
        String json = mapper.writeValueAsString(block);

        // Java toolUseId → JSON tool_use_id
        assertTrue(json.contains("\"tool_use_id\":\"toolu_xxx\""),
                "Expected snake_case 'tool_use_id' in JSON, got: " + json);
        assertTrue(json.contains("\"type\":\"tool_result\""));
    }


    @Test
    void should_parse_real_anthropic_response() throws Exception {
        String json = Files.readString(Path.of(
                "src/test/resources/fixtures/sample_response.json"));
        ObjectMapper mapper = JacksonConfig.newMapper();

        CreateMessageResponse resp = mapper.readValue(json, CreateMessageResponse.class);

        assertEquals("msg_01ABC", resp.getId());
        assertEquals("tool_use", resp.getStopReason());
        assertTrue(resp.needsToolExecution());

        assertEquals(2, resp.getContent().size());
        assertInstanceOf(TextBlock.class, resp.getContent().get(0));
        assertInstanceOf(ToolUseBlock.class, resp.getContent().get(1));

        ToolUseBlock tu = resp.toolUses().get(0);
        assertEquals("toolu_01XYZ", tu.getId());
        assertEquals("bash", tu.getName());
        assertEquals("ls -la", tu.getInput().get("command").asText());

        assertEquals(142, resp.getUsage().getInputTokens());
        assertEquals(47, resp.getUsage().getOutputTokens());
        assertEquals(189, resp.getUsage().totalTokens());
    }
}
