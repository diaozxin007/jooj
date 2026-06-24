package com.xilidou.marvis.memory;

import com.xilidou.marvis.http.AnthropicClient;
import com.xilidou.marvis.http.MockAnthropicClient;
import com.xilidou.marvis.http.ResponseFixtures;
import com.xilidou.marvis.http.dto.MessageParam;
import com.xilidou.marvis.http.dto.TextBlock;
import com.xilidou.marvis.http.dto.ToolResultBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link MemorySelector} 的核心行为。
 *
 * <p>9 个关键场景:
 * <ol>
 *   <li>无 memory 文件 → 空列表</li>
 *   <li>有 memory + LLM 返回正常 JSON → 选中正确文件</li>
 *   <li>LLM 返回带 preamble 的 JSON → 仍能抠出</li>
 *   <li>LLM 返回 [] → 空列表(模型说"无相关")</li>
 *   <li>LLM 抛异常 → 关键词回退</li>
 *   <li>LLM 返回坏 JSON → 关键词回退</li>
 *   <li>关键词回退:case-insensitive substring 匹配</li>
 *   <li>无 user 文本(全是 tool_result)→ 空列表</li>
 *   <li>load() 渲染 &lt;relevant_memories&gt; 格式</li>
 * </ol>
 */
class MemorySelectorTest {

    private static MemoryStore freshStore(Path tempDir) {
        MemoryStore store = new MemoryStore(new MemoryConfig(tempDir, "MEMORY.md", 4096, 10));
        return store;
    }

    private static MemoryFile sample(String name, MemoryFile.Type type, String desc, String body) {
        return MemoryFile.of(name, type, desc, body);
    }

    private static MessageParam userText(String text) {
        return MessageParam.user(text);
    }

    private static MessageParam userToolResult(String id, String content) {
        return new MessageParam("user",
                new ArrayList<>(List.of(ToolResultBlock.ofText(id, content))));
    }

    private static MessageParam userTextBlocks(String text) {
        return new MessageParam("user", List.of(new TextBlock(text)));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 1:空 store
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("select with no memories should return empty")
    void select_no_memories(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        // mock 不应被调用
        AnthropicClient mock = MockAnthropicClient.ofResponses();
        MemorySelector selector = new MemorySelector(store, mock, "test-model");

        List<String> out = selector.select(List.of(userText("hi")));
        assertTrue(out.isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 2:LLM 返回 [0, 2] → 选中第 0 和第 2 个
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM returns valid JSON array → picks correct files")
    void select_via_llm_happy_path(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        store.write(sample("user-tabs", MemoryFile.Type.USER, "User prefers tabs", "Use tabs."));
        store.write(sample("project-auth", MemoryFile.Type.PROJECT, "Auth module rewrite", "Rewriting auth."));
        store.write(sample("ref-bugs", MemoryFile.Type.REFERENCE, "Where bugs are tracked", "Linear INGEST."));

        // Mock LLM 选 0 和 2
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("[0, 2]"));
        MemorySelector selector = new MemorySelector(store, mock, "test-model");

        List<String> out = selector.select(List.of(userText("Where do I track bugs?")));

        assertEquals(2, out.size());
        // store.list() 按 mtime 倒序,最新写的 ref-bugs 是第 0 个
        // index=0 应该是最新写的 ref-bugs.md
        // index=2 应该是最早写的 user-tabs.md
        assertTrue(out.contains("ref-bugs.md"));
        assertTrue(out.contains("user-tabs.md"));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 3:LLM 返回带 preamble 的 JSON
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM with preamble text → still extracts JSON array")
    void select_extracts_json_from_preamble(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        store.write(sample("foo", MemoryFile.Type.USER, "foo desc", "body"));

        // 模型话比较多,但 [0] 还是能抠出来
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("Sure, the relevant memory is: [0]\nThanks!"));
        MemorySelector selector = new MemorySelector(store, mock, "test-model");

        List<String> out = selector.select(List.of(userText("about foo")));
        assertEquals(1, out.size());
        assertEquals("foo.md", out.get(0));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 4:LLM 返回 [] → 空
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM returns empty array → no selection")
    void select_llm_returns_empty(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        store.write(sample("foo", MemoryFile.Type.USER, "foo desc", "body"));

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("[]"));
        MemorySelector selector = new MemorySelector(store, mock, "test-model");

        List<String> out = selector.select(List.of(userText("totally unrelated query")));
        assertTrue(out.isEmpty(), "LLM 说无相关时返回空,不退化到关键词");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 5:LLM 抛异常 → 关键词回退
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM throws → fall back to keyword matching")
    void select_falls_back_on_llm_failure(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        store.write(sample("user-tabs", MemoryFile.Type.USER, "User prefers tabs", "body"));
        store.write(sample("project-auth", MemoryFile.Type.PROJECT, "Auth module rewrite", "body"));

        AnthropicClient throwing = req -> {
            throw new RuntimeException("simulated LLM failure");
        };
        MemorySelector selector = new MemorySelector(store, throwing, "test-model");

        // Query 含 "tabs" 关键词 → 关键词匹配应该选中 user-tabs
        List<String> out = selector.select(List.of(userText("How should I handle tabs in code?")));
        assertEquals(1, out.size());
        assertEquals("user-tabs.md", out.get(0));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 6:LLM 返回完全没 JSON → 关键词回退
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM returns prose without JSON → fall back to keyword matching")
    void select_falls_back_on_no_json(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        store.write(sample("user-tabs", MemoryFile.Type.USER, "User prefers tabs", "body"));

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("I cannot determine what's relevant here."));
        MemorySelector selector = new MemorySelector(store, mock, "test-model");

        List<String> out = selector.select(List.of(userText("about tabs")));
        // 没 JSON 数组 → 走关键词,"tabs" 出现在 description 里 → 选中
        assertEquals(1, out.size());
        assertEquals("user-tabs.md", out.get(0));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 7:关键词大小写不敏感 + 多关键词命中
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("keyword fallback: case-insensitive substring on name+description")
    void keyword_fallback_case_insensitive(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        store.write(sample("auth-module", MemoryFile.Type.PROJECT, "Authentication rewrite", "body"));
        store.write(sample("style-tabs", MemoryFile.Type.USER, "User likes tabs", "body"));
        store.write(sample("perf-cache", MemoryFile.Type.FEEDBACK, "Use cache for queries", "body"));

        // null client → 直接走关键词路径
        MemorySelector selector = new MemorySelector(store, null, null, 5);

        // 关键词:authentication(大写)→ 匹配 description "Authentication"
        List<String> out = selector.select(List.of(userText("AUTHENTICATION concerns")));
        assertEquals(1, out.size());
        assertEquals("auth-module.md", out.get(0));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 8:全是 tool_result 的对话 → 空(没有 user 文本)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("messages with only tool_results → no recent user text → empty")
    void select_skips_tool_result_only_user_messages(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        store.write(sample("foo", MemoryFile.Type.USER, "foo desc", "body"));

        // mock 不应被调用
        AnthropicClient mock = MockAnthropicClient.ofResponses();
        MemorySelector selector = new MemorySelector(store, mock, "test-model");

        // 只有 tool_result 的 user 消息(没有 user 输入文本)
        List<String> out = selector.select(List.of(
                userToolResult("tu_1", "some tool output"),
                userToolResult("tu_2", "another output")));

        assertTrue(out.isEmpty(), "全是 tool_result 应被识别为无 user 文本,跳过 LLM 调用");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 9:load() 渲染 <relevant_memories> 格式
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("load() should render selected memories as <relevant_memories> block")
    void load_renders_relevant_memories_block(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        store.write(sample("user-tabs", MemoryFile.Type.USER, "tabs preference",
                "Use tabs not spaces."));
        store.write(sample("project-auth", MemoryFile.Type.PROJECT, "auth rewrite",
                "Rewriting auth in compliance."));

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("[0, 1]"));
        MemorySelector selector = new MemorySelector(store, mock, "test-model");

        String loaded = selector.load(List.of(userText("anything")));

        assertTrue(loaded.startsWith("<relevant_memories>"),
                "应以 <relevant_memories> 开头");
        assertTrue(loaded.endsWith("</relevant_memories>"),
                "应以 </relevant_memories> 结尾");
        assertTrue(loaded.contains("Use tabs not spaces."),
                "应包含选中 memory 的 body: " + loaded);
        assertTrue(loaded.contains("Rewriting auth in compliance."));
        // 每条 memory 包在 <memory name="..."> 里
        assertTrue(loaded.contains("<memory name=\"user-tabs\">"));
        assertTrue(loaded.contains("<memory name=\"project-auth\">"));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 10:load() 无相关时返回空字符串
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("load() with no selected → empty string")
    void load_returns_empty_when_nothing_selected(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        // 空 store
        MockAnthropicClient mock = MockAnthropicClient.ofResponses();
        MemorySelector selector = new MemorySelector(store, mock, "test-model");

        assertEquals("", selector.load(List.of(userText("hi"))));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 11:maxItems 限制
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("maxItems caps the number of selected memories")
    void max_items_cap(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        for (int i = 0; i < 10; i++) {
            store.write(sample("mem-" + i, MemoryFile.Type.USER, "desc " + i, "body"));
        }

        // LLM 想选 8 个,但 maxItems=3
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("[0, 1, 2, 3, 4, 5, 6, 7]"));
        MemorySelector selector = new MemorySelector(store, mock, "test-model", 3);

        List<String> out = selector.select(List.of(userText("query")));
        assertEquals(3, out.size(), "maxItems=3 应该截断到 3 个");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 12:user message 是 List<TextBlock> 而非 String
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("user message with TextBlock list (not plain string) is also handled")
    void handles_textblock_user_message(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        store.write(sample("user-tabs", MemoryFile.Type.USER, "User prefers tabs", "body"));

        // null client → 关键词路径
        MemorySelector selector = new MemorySelector(store, null, null);

        // user message 用 TextBlock 列表(而非 String)
        List<String> out = selector.select(List.of(userTextBlocks("about tabs and indentation")));
        assertEquals(1, out.size());
        assertEquals("user-tabs.md", out.get(0));
    }
}
