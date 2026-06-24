package com.xilidou.marvis.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xilidou.marvis.http.AnthropicClient;
import com.xilidou.marvis.http.MockAnthropicClient;
import com.xilidou.marvis.http.ResponseFixtures;
import com.xilidou.marvis.http.dto.CreateMessageRequest;
import com.xilidou.marvis.http.dto.CreateMessageResponse;
import com.xilidou.marvis.http.dto.MessageParam;
import com.xilidou.marvis.http.dto.TextBlock;
import com.xilidou.marvis.http.dto.ToolResultBlock;
import com.xilidou.marvis.http.dto.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link HistoryCompactor} (L4) 的核心行为。
 *
 * <p>6 个关键场景:
 * <ol>
 *   <li>messages 太短 → 不动(没有可摘要的中段)</li>
 *   <li>正常摘要:LLM 返回摘要 → 中段被替换 + transcript 落盘</li>
 *   <li>归档文件内容正确(jsonl,每行一条 MessageParam JSON)</li>
 *   <li>LLM 调用失败 → 不替换 messages,返回 false</li>
 *   <li>摘要超长 → 截断到 summaryMaxChars + "..."</li>
 *   <li>tool_use ↔ tool_result 边界保护(摘要切口不能拆配对)</li>
 * </ol>
 */
class HistoryCompactorTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private static MessageParam userText(String text) {
        return MessageParam.user(text);
    }

    private static MessageParam assistantText(String text) {
        return new MessageParam("assistant", List.of(new TextBlock(text)));
    }

    private static MessageParam assistantToolUse(String id) {
        JsonNode input = JSON.objectNode();
        return new MessageParam("assistant", List.of(new ToolUseBlock(id, "test_tool", input)));
    }

    private static MessageParam userToolResult(String id, String content) {
        return new MessageParam("user", new ArrayList<>(List.of(ToolResultBlock.ofText(id, content))));
    }

    /** 用 @TempDir 路径构造 config,L1/L2/L3 字段不影响 L4。*/
    private static CompactConfig configWithDir(Path tempDir, int headKeep, int tailKeep, int summaryMaxChars) {
        return new CompactConfig(
                50, 3, 3, 120,                  // L1/L2 默认
                10000, tempDir.resolve("task-outputs"),  // L3 用 tempDir
                headKeep, tailKeep,
                tempDir.resolve("transcripts"), summaryMaxChars);
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 1:messages 太短 — 不动
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L4 should not summarize when head + 1 + tail >= total")
    void should_skip_when_too_short(@TempDir Path tempDir) {
        // head=3 + tail=10 = 13;消息只有 13 条 → 没空间(headEnd + 1 >= tailStart)
        AnthropicClient client = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("(should not be called)"));
        HistoryCompactor h = new HistoryCompactor(
                configWithDir(tempDir, 3, 10, 500), client, "test-model");

        List<MessageParam> messages = new ArrayList<>();
        for (int i = 0; i < 13; i++) messages.add(userText("m" + i));

        boolean changed = h.apply(messages);

        assertFalse(changed, "13 条 = head 3 + tail 10,中间没空间,应跳过");
        assertEquals(13, messages.size(), "messages 不应被修改");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 2:正常摘要 — 中段被替换 + transcript 落盘
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L4 should summarize middle and replace with summary message")
    void should_summarize_and_replace_middle(@TempDir Path tempDir) {
        AnthropicClient client = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("Agent read 10 files and built a summary."));
        HistoryCompactor h = new HistoryCompactor(
                configWithDir(tempDir, 2, 3, 500), client, "test-model");

        List<MessageParam> messages = new ArrayList<>();
        // 头 2:m0/m1
        messages.add(userText("query"));
        messages.add(assistantText("ok"));
        // 中间 10:m2~m11(将被摘要)
        for (int i = 0; i < 10; i++) messages.add(userText("middle-" + i));
        // 尾 3:m12/m13/m14
        messages.add(userText("recent-1"));
        messages.add(assistantText("recent-2"));
        messages.add(userText("recent-3"));

        boolean changed = h.apply(messages);

        assertTrue(changed, "应触发摘要");
        // 头 2 + 摘要 1 + 尾 3 = 6
        assertEquals(6, messages.size(), "替换后应剩 head + summary + tail = 6 条");

        // 头部保留
        assertEquals("query", messages.get(0).getContent());
        assertEquals("assistant", messages.get(1).getRole());

        // 摘要在 idx 2
        MessageParam summaryMsg = messages.get(2);
        assertEquals("user", summaryMsg.getRole());
        String s = (String) summaryMsg.getContent();
        assertTrue(s.startsWith(HistoryCompactor.SUMMARY_PREFIX), "应以 SUMMARY_PREFIX 开头: " + s);
        assertTrue(s.contains("Agent read 10 files"), "应含 LLM 返回的摘要文本: " + s);
        assertTrue(s.contains("transcript-"), "应含 transcript 文件路径: " + s);

        // 尾部保留
        assertEquals("recent-1", messages.get(3).getContent());
        assertEquals("recent-2", ((List<?>) messages.get(4).getContent()).stream()
                .filter(b -> b instanceof TextBlock).map(b -> ((TextBlock) b).getText())
                .findFirst().orElse(""));
        assertEquals("recent-3", messages.get(5).getContent());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 3:归档文件内容(jsonl 格式)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L4 should archive middle messages as jsonl before summarizing")
    void should_archive_middle_to_transcript_jsonl(@TempDir Path tempDir) throws IOException {
        AnthropicClient client = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("summary text"));
        HistoryCompactor h = new HistoryCompactor(
                configWithDir(tempDir, 2, 3, 500), client, "test-model");

        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("query"));
        messages.add(assistantText("ok"));
        for (int i = 0; i < 5; i++) messages.add(userText("archived-" + i));
        for (int i = 0; i < 3; i++) messages.add(userText("recent-" + i));

        h.apply(messages);

        // .transcripts 目录存在 + 含 1 个 transcript-*.jsonl 文件
        Path transcriptDir = tempDir.resolve("transcripts");
        assertTrue(Files.isDirectory(transcriptDir));

        try (var stream = Files.list(transcriptDir)) {
            List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
            assertEquals(1, files.size(), "应该正好有 1 个 jsonl 归档");
            // 文件每行是一条 MessageParam JSON
            List<String> lines = Files.readAllLines(files.get(0), StandardCharsets.UTF_8);
            assertEquals(5, lines.size(), "中间 5 条都应被归档");
            // 第一行应该含 archived-0
            assertTrue(lines.get(0).contains("archived-0"));
            assertTrue(lines.get(4).contains("archived-4"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 4:LLM 调用失败 — messages 不被修改
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L4 should not modify messages when LLM call throws")
    void should_skip_on_llm_failure(@TempDir Path tempDir) {
        AnthropicClient throwing = req -> {
            throw new RuntimeException("simulated LLM failure");
        };
        HistoryCompactor h = new HistoryCompactor(
                configWithDir(tempDir, 2, 3, 500), throwing, "test-model");

        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("query"));
        messages.add(assistantText("ok"));
        for (int i = 0; i < 10; i++) messages.add(userText("middle-" + i));
        for (int i = 0; i < 3; i++) messages.add(userText("recent-" + i));

        int sizeBefore = messages.size();
        boolean changed = h.apply(messages);

        assertFalse(changed, "LLM 失败应返回 false");
        assertEquals(sizeBefore, messages.size(),
                "messages 不应被修改(数据保留,等待外层抛错)");
        // 内容也应原封不动
        assertEquals("query", messages.get(0).getContent());
        assertEquals("middle-0", messages.get(2).getContent());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 5:摘要超长 — 截断到 summaryMaxChars + "..."
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L4 should truncate over-long summaries to summaryMaxChars")
    void should_truncate_long_summary(@TempDir Path tempDir) {
        // LLM 返回 800 字符,但 summaryMaxChars=100
        StringBuilder longText = new StringBuilder();
        while (longText.length() < 800) longText.append("very long summary text. ");
        AnthropicClient client = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(longText.toString()));
        HistoryCompactor h = new HistoryCompactor(
                configWithDir(tempDir, 2, 3, 100), client, "test-model");

        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("q"));
        messages.add(assistantText("a"));
        for (int i = 0; i < 5; i++) messages.add(userText("m" + i));
        for (int i = 0; i < 3; i++) messages.add(userText("r" + i));

        h.apply(messages);

        String summaryContent = (String) messages.get(2).getContent();
        // SUMMARY_PREFIX + (...messages archived to ...): + truncated 100 chars + "..."
        assertTrue(summaryContent.endsWith("..."), "超长摘要应被截断 + 加 ...");
        // 实际摘要部分(冒号 + 空格之后)长度 ≈ 100 + "..."
        int colonIdx = summaryContent.lastIndexOf("): ");
        String summaryPart = summaryContent.substring(colonIdx + 3);
        assertTrue(summaryPart.length() <= 103, "摘要部分应 ≤ 100 + '...': " + summaryPart.length());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 6:tool_use ↔ tool_result 边界保护
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L4 should respect tool_use ↔ tool_result pairing at cut points")
    void should_protect_tool_use_pair_at_cut_points(@TempDir Path tempDir) {
        AnthropicClient client = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("summary"));
        HistoryCompactor h = new HistoryCompactor(
                configWithDir(tempDir, 2, 3, 500), client, "test-model");

        List<MessageParam> messages = new ArrayList<>();
        // 头部:safe
        messages.add(userText("query"));
        messages.add(assistantText("ok"));
        // 中段(将被摘要): m2~m6
        for (int i = 0; i < 5; i++) messages.add(userText("middle-" + i));
        // 尾部 3 条,但故意把第一条做成 tool_result,前一条是 tool_use
        // tailStart 原值 = total - 3
        // 让 messages[tailStart-1] 是 tool_use, messages[tailStart] 是 tool_result
        messages.add(assistantToolUse("tu_42"));                // 这是中段最后一条
        messages.add(userToolResult("tu_42", "result content")); // tailStart 第一条
        messages.add(userText("after-tail"));
        messages.add(userText("more-tail"));
        // total = 11, tailStart = 11-3 = 8
        // messages[8]=tool_result(tu_42), messages[7]=tool_use(tu_42)
        // adjustTailStart 应推前到 7

        h.apply(messages);

        // 验证替换后没出现孤儿 tool_use:每条 assistant(tool_use) 后必紧跟 user(tool_result)
        for (int i = 0; i < messages.size(); i++) {
            if (MessageBoundary.hasToolUse(messages.get(i))) {
                assertTrue(i + 1 < messages.size()
                                && MessageBoundary.isToolResult(messages.get(i + 1)),
                        "L4 摘要后 tool_use 必须紧跟 tool_result, 实际 idx=" + i);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 7:LLM 返回空字符串 — 不替换
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L4 should not modify messages when LLM returns empty summary")
    void should_skip_on_empty_summary(@TempDir Path tempDir) {
        // LLM 返回空 text
        AnthropicClient client = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(""));
        HistoryCompactor h = new HistoryCompactor(
                configWithDir(tempDir, 2, 3, 500), client, "test-model");

        List<MessageParam> messages = new ArrayList<>();
        messages.add(userText("q"));
        messages.add(assistantText("a"));
        for (int i = 0; i < 10; i++) messages.add(userText("m" + i));
        for (int i = 0; i < 3; i++) messages.add(userText("r" + i));

        int sizeBefore = messages.size();
        boolean changed = h.apply(messages);

        assertFalse(changed, "LLM 返回空字符串应返回 false");
        assertEquals(sizeBefore, messages.size(), "messages 不应被修改");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 8:Pipeline 入口 — hasReactiveSupport / reactiveCompact
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CompactPipeline.hasReactiveSupport reflects whether client was injected")
    void pipeline_reactive_support_flag() {
        CompactPipeline withoutClient = new CompactPipeline();
        assertFalse(withoutClient.hasReactiveSupport(),
                "默认无参构造器应禁用 L4");

        CompactPipeline withClient = new CompactPipeline(
                new CompactConfig(),
                req -> ResponseFixtures.endTurn("ok"),
                "test-model");
        assertTrue(withClient.hasReactiveSupport(), "注入 client 后应启用 L4");
    }

    @Test
    @DisplayName("CompactPipeline.reactiveCompact returns false when L4 disabled")
    void pipeline_reactive_returns_false_without_client() {
        CompactPipeline noL4 = new CompactPipeline(new CompactConfig());
        List<MessageParam> messages = new ArrayList<>();
        for (int i = 0; i < 100; i++) messages.add(userText("m" + i));

        assertFalse(noL4.reactiveCompact(messages),
                "无 client 时 reactiveCompact 应优雅返回 false");
        assertEquals(100, messages.size(), "messages 不应被修改");
    }
}
