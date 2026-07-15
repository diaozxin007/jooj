package com.xilidou.jooj.memory;

import com.xilidou.jooj.http.MockAnthropicClient;
import com.xilidou.jooj.http.ResponseFixtures;
import com.xilidou.jooj.llm.LlmClient;
import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link MemoryExtractor} 的核心行为。
 *
 * <p>10 个关键场景:
 * <ol>
 *   <li>正常路径:LLM 返回有效 JSON 数组 → 写入对应文件</li>
 *   <li>LLM 返回空数组 [] → 不写</li>
 *   <li>LLM 返回带 preamble 的 JSON → 仍能抠出</li>
 *   <li>LLM 抛异常 → 不写,不抛(优雅降级)</li>
 *   <li>LLM 返回非 JSON → 不写</li>
 *   <li>JSON 单条缺字段 → 跳过该条,其他正常写</li>
 *   <li>JSON 单条 type 非法 → 兜底 USER</li>
 *   <li>messages 全 tool_result(无对话文本)→ 不调 LLM</li>
 *   <li>client=null(Extractor 禁用)→ 直接返回 0</li>
 *   <li>已有 memory 的 catalog 拼到 prompt 里(让 LLM 知道哪些已有)</li>
 * </ol>
 */
class MemoryExtractorTest {

    private static MemoryStore freshStore(Path dir) {
        return new MemoryStore(new MemoryConfig(dir, "MEMORY.md", 4096, 10));
    }

    private static LlmMessage userText(String text) {
        return LlmMessage.userText(text);
    }

    private static LlmMessage assistantText(String text) {
        return LlmMessage.assistant(List.of(new LlmText(text)));
    }

    private static LlmMessage userToolResult(String id, String content) {
        return LlmMessage.toolResults(
                new ArrayList<>(List.of(LlmToolResult.success(id, content))));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 1:正常路径
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM returns valid JSON array → writes memories")
    void extract_happy_path(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        String llmJson =
                "[{\"name\":\"user-prefer-tabs\",\"type\":\"user\"," +
                        "\"description\":\"User prefers tabs\"," +
                        "\"body\":\"Use tabs not spaces.\"}," +
                        "{\"name\":\"feedback-no-mock\",\"type\":\"feedback\"," +
                        "\"description\":\"Don't mock DB\"," +
                        "\"body\":\"User has local Postgres.\"}]";
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(llmJson));
        MemoryExtractor extractor = new MemoryExtractor(store, mock, "test-model");

        List<LlmMessage> messages = List.of(
                userText("I prefer tabs and don't mock my Postgres."),
                assistantText("ok, noted."));

        int written = extractor.extract(messages);

        assertEquals(2, written);
        // 验证文件落盘
        assertTrue(store.read("user-prefer-tabs.md").isPresent());
        assertTrue(store.read("feedback-no-mock.md").isPresent());
        // 验证字段正确
        MemoryFile m1 = store.read("user-prefer-tabs.md").get();
        assertEquals(MemoryFile.Type.USER, m1.getType());
        assertEquals("User prefers tabs", m1.getDescription());
        assertTrue(m1.getBody().contains("Use tabs not spaces."));

        MemoryFile m2 = store.read("feedback-no-mock.md").get();
        assertEquals(MemoryFile.Type.FEEDBACK, m2.getType());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 2:LLM 返回 [] → 不写
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM returns empty array → no writes")
    void extract_empty_array(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("[]"));
        MemoryExtractor extractor = new MemoryExtractor(store, mock, "test-model");

        int written = extractor.extract(List.of(userText("just hello")));

        assertEquals(0, written);
        assertTrue(store.list().isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 3:LLM 带 preamble 的 JSON → 仍能抠出
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM with preamble → still extracts JSON")
    void extract_json_from_preamble(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        String llmText = "Sure, here are the extracted memories:\n" +
                "[{\"name\":\"foo\",\"type\":\"user\"," +
                "\"description\":\"foo desc\",\"body\":\"foo body\"}]\n" +
                "Done.";
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(llmText));
        MemoryExtractor extractor = new MemoryExtractor(store, mock, "test-model");

        int written = extractor.extract(List.of(userText("test")));

        assertEquals(1, written);
        assertTrue(store.read("foo.md").isPresent());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 4:LLM 抛异常 → 不写,不抛(优雅降级)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM throws → no writes, no exception")
    void extract_llm_failure_graceful(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        LlmClient throwing = req -> {
            throw new RuntimeException("simulated LLM failure");
        };
        MemoryExtractor extractor = new MemoryExtractor(store, throwing, "test-model");

        int written = assertDoesNotThrow(
                () -> extractor.extract(List.of(userText("test"))));

        assertEquals(0, written);
        assertTrue(store.list().isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 5:LLM 返回非 JSON → 不写
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM returns prose without JSON → no writes")
    void extract_no_json(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("I don't see any new information to extract."));
        MemoryExtractor extractor = new MemoryExtractor(store, mock, "test-model");

        int written = extractor.extract(List.of(userText("test")));

        assertEquals(0, written);
        assertTrue(store.list().isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 6:单条缺必要字段 → 跳过该条,其他正常写
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Item missing required fields → skip that item, write others")
    void extract_skips_invalid_items(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        // 第 1 条:OK
        // 第 2 条:缺 description
        // 第 3 条:缺 body
        // 第 4 条:OK
        String llmJson =
                "[{\"name\":\"good-1\",\"type\":\"user\"," +
                        "\"description\":\"d1\",\"body\":\"b1\"}," +
                        "{\"name\":\"missing-desc\",\"type\":\"user\",\"body\":\"b\"}," +
                        "{\"name\":\"missing-body\",\"type\":\"user\",\"description\":\"d\"}," +
                        "{\"name\":\"good-2\",\"type\":\"user\"," +
                        "\"description\":\"d2\",\"body\":\"b2\"}]";
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(llmJson));
        MemoryExtractor extractor = new MemoryExtractor(store, mock, "test-model");

        int written = extractor.extract(List.of(userText("test")));

        assertEquals(2, written, "只该写入 2 条完整的");
        assertTrue(store.read("good-1.md").isPresent());
        assertTrue(store.read("good-2.md").isPresent());
        assertTrue(store.read("missing-desc.md").isEmpty());
        assertTrue(store.read("missing-body.md").isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 7:type 非法 → 兜底 USER
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Item with invalid type → falls back to USER")
    void extract_invalid_type_defaults_user(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        String llmJson = "[{\"name\":\"foo\",\"type\":\"WeirdType\"," +
                "\"description\":\"d\",\"body\":\"b\"}]";
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(llmJson));
        MemoryExtractor extractor = new MemoryExtractor(store, mock, "test-model");

        extractor.extract(List.of(userText("test")));

        MemoryFile m = store.read("foo.md").orElseThrow();
        assertEquals(MemoryFile.Type.USER, m.getType(),
                "未知 type 应兜底到 USER");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 8:messages 全是 tool_result → 不调 LLM
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("messages with only tool_results → no LLM call")
    void extract_skips_tool_result_only_messages(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        // mock 不应被调用(空 responses → 调到就报 "too many calls")
        LlmClient mock = MockAnthropicClient.ofResponses();
        MemoryExtractor extractor = new MemoryExtractor(store, mock, "test-model");

        int written = extractor.extract(List.of(
                userToolResult("tu_1", "some output"),
                userToolResult("tu_2", "another output")));

        assertEquals(0, written, "全 tool_result 应被识别为无对话文本");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 9:client = null → Extractor 禁用
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("client=null disables extractor → returns 0")
    void extract_disabled_when_no_client(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        MemoryExtractor extractor = new MemoryExtractor(store, null, null);

        int written = extractor.extract(List.of(userText("anything")));

        assertEquals(0, written);
        assertTrue(store.list().isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 10:已有 memory 的 catalog 拼到 prompt
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("existing memories appear in prompt so LLM avoids duplicates")
    void extract_includes_existing_in_prompt(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        // 预先写一条
        store.write(MemoryFile.of("user-prefer-tabs", MemoryFile.Type.USER,
                "User prefers tabs", "body"));

        // Mock 客户端,验证 prompt 含 existing
        final String[] capturedPrompt = {null};
        LlmClient capturing = req -> {
            // 抠 prompt 出来检查(canonical LlmMessage.content 是 List<LlmContent>)
            LlmMessage first = req.getMessages().get(0);
            for (LlmContent c : first.getContent()) {
                if (c instanceof LlmText t) {
                    capturedPrompt[0] = t.getText();
                    break;
                }
            }
            return com.xilidou.jooj.llm.domain.LlmResponse.builder()
                    .id("mock").model("test-model")
                    .content(List.of(new LlmText("[]")))
                    .stopReason(com.xilidou.jooj.llm.domain.LlmStopReason.END_TURN)
                    .build();
        };
        MemoryExtractor extractor = new MemoryExtractor(store, capturing, "test-model");

        extractor.extract(List.of(userText("anything")));

        assertNotNull(capturedPrompt[0]);
        assertTrue(capturedPrompt[0].contains("user-prefer-tabs"),
                "prompt 应包含已有 memory 的 name");
        assertTrue(capturedPrompt[0].contains("User prefers tabs"),
                "prompt 应包含已有 memory 的 description");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 11:对话拼接渲染 — role: content 格式
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dialogue rendering: role: content per line")
    void render_dialogue_format(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        MemoryExtractor extractor = new MemoryExtractor(store, null, null);

        List<LlmMessage> messages = List.of(
                userText("hello"),
                assistantText("hi there"));

        String dialogue = extractor.renderRecentDialogue(messages);

        assertTrue(dialogue.contains("user: hello"));
        assertTrue(dialogue.contains("assistant: hi there"));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 12:JSON 解析容错 — 仅最外层数组
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("JSON parse should handle nested objects in array")
    void parse_handles_nested_json(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        MemoryExtractor extractor = new MemoryExtractor(store, null, null);

        String text = "Result: [{\"name\":\"foo\",\"type\":\"user\"," +
                "\"description\":\"d\",\"body\":\"b\"," +
                "\"meta\":{\"nested\":true}}]";
        var items = extractor.parseExtractedItems(text);
        assertNotNull(items);
        assertEquals(1, items.size());
        assertEquals("foo", items.get(0).get("name"));
    }
}
