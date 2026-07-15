package com.xilidou.jooj.memory;

import com.xilidou.jooj.http.MockAnthropicClient;
import com.xilidou.jooj.http.ResponseFixtures;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link BackgroundReviewer} 的核心行为(s21 Demo 26 / Hermes Tier 3 P3.1)。
 *
 * <p>跟 {@link MemoryExtractorTest} 模式平行,但聚焦 Reviewer 独有特征:
 * <ul>
 *   <li>type 缺失时默认 FEEDBACK(不是 Extractor 的 USER)</li>
 *   <li>对话太短(< 4 message)直接跳过不调 LLM(模式判断需要上下文)</li>
 *   <li>tool_use name 进 prompt(Reviewer 关心工作流模式)</li>
 *   <li>prompt 强调"PATTERN 不是 fact",显式拒绝单次事实</li>
 *   <li>LLM 抛异常 → warn 不抛(异步路径不该挡 caller)</li>
 *   <li>client=null → 直接返 0(Reviewer 禁用)</li>
 * </ul>
 */
class BackgroundReviewerTest {

    private static MemoryStore freshStore(Path dir) {
        return new MemoryStore(new MemoryConfig(dir, "MEMORY.md", 4096, 10));
    }

    private static LlmMessage userText(String text) {
        return LlmMessage.userText(text);
    }

    private static LlmMessage assistantText(String text) {
        return LlmMessage.assistant(List.of(new LlmText(text)));
    }

    private static LlmMessage assistantToolUse(String name) {
        return LlmMessage.assistant(
                List.of(new LlmToolCall("tu_x", name, JsonNodeFactory.instance.objectNode())));
    }

    private static LlmMessage userToolResult(String id, String content) {
        return LlmMessage.toolResults(
                new ArrayList<>(List.of(LlmToolResult.success(id, content))));
    }

    private static List<LlmMessage> longishConversation() {
        // 6 messages,够 Reviewer 看出"模式"
        return List.of(
                userText("Use grep to find usages of foo."),
                assistantText("Sure, running grep."),
                userText("No, please use ripgrep instead, it's faster."),
                assistantText("Got it, switching to rg."),
                userText("Now find usages of bar — and again, ripgrep please, not grep."),
                assistantText("Will do.")
        );
    }

    @Test
    @DisplayName("Happy path:LLM 返回有效提案 → 写入 memory(type 默认 FEEDBACK)")
    void review_happy_path(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        String llmJson =
                "[{\"name\":\"feedback-prefer-ripgrep\"," +
                        "\"description\":\"User prefers ripgrep over grep\"," +
                        "\"body\":\"Always use rg, not grep.\"}]"; // 注意没传 type
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(llmJson));
        BackgroundReviewer reviewer = new BackgroundReviewer(store, mock, "test-model");

        int written = reviewer.review(longishConversation());

        assertEquals(1, written);
        var saved = store.list();
        assertEquals(1, saved.size());
        assertEquals("feedback-prefer-ripgrep", saved.get(0).getName());
        assertEquals(MemoryFile.Type.FEEDBACK, saved.get(0).getType(),
                "type 缺失时 Reviewer 默认 FEEDBACK,Extractor 默认 USER —— 这是边界");
    }

    @Test
    @DisplayName("LLM 返回空数组 [] → 不写(没观察到模式)")
    void review_empty_proposal(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("[]"));
        BackgroundReviewer reviewer = new BackgroundReviewer(store, mock, "test-model");

        int written = reviewer.review(longishConversation());

        assertEquals(0, written);
        assertTrue(store.list().isEmpty());
    }

    @Test
    @DisplayName("对话太短(<4 messages)直接跳过 → 不调 LLM,不写")
    void review_skip_short_conversation(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        // mock 不准备任何响应:如果代码错误调了 LLM,mock 会抛 → 测试 fail
        MockAnthropicClient mock = MockAnthropicClient.ofResponses();
        BackgroundReviewer reviewer = new BackgroundReviewer(store, mock, "test-model");

        // 只有 3 条 message 谈不上"模式"
        int written = reviewer.review(List.of(
                userText("hi"),
                assistantText("hi back"),
                userText("bye")
        ));

        assertEquals(0, written);
        assertTrue(store.list().isEmpty());
    }

    @Test
    @DisplayName("LLM 抛异常 → warn 不抛(异步路径不能挡 caller)")
    void review_llm_failure_does_not_throw(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        MockAnthropicClient mock = MockAnthropicClient.throwing(
                new RuntimeException("simulated LLM failure"));
        BackgroundReviewer reviewer = new BackgroundReviewer(store, mock, "test-model");

        int written = assertDoesNotThrow(() -> reviewer.review(longishConversation()));
        assertEquals(0, written);
    }

    @Test
    @DisplayName("client=null → Reviewer 禁用,直接返 0")
    void review_disabled_when_client_null(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        BackgroundReviewer reviewer = new BackgroundReviewer(store, null, null);

        int written = reviewer.review(longishConversation());
        assertEquals(0, written);
    }

    @Test
    @DisplayName("buildReviewPrompt 强调 PATTERN 不是 fact + 列已有 catalog")
    void prompt_emphasizes_patterns_not_facts() {
        String prompt = BackgroundReviewer.buildReviewPrompt(
                "user: I prefer ripgrep\nassistant: ok",
                "- existing-mem: stuff");
        assertTrue(prompt.contains("PATTERN"), "prompt 必须强调 PATTERN");
        assertTrue(prompt.contains("DO NOT propose"), "prompt 必须有 DO NOT 段防 LLM 越界");
        assertTrue(prompt.contains("Single-shot facts"), "prompt 必须显式拒绝单次事实(留给 Extractor)");
        assertTrue(prompt.contains("existing-mem"), "已有 memory 必须列出来防重复提案");
        assertTrue(prompt.toLowerCase().contains("kebab-case"), "name 格式约定");
    }

    @Test
    @DisplayName("toMemoryFile:type 显式 'user' 时不被 default FEEDBACK 覆盖")
    void to_memory_file_respects_explicit_type() {
        var item = new java.util.HashMap<String, Object>();
        item.put("name", "user-prefers-vim");
        item.put("type", "user");
        item.put("description", "user prefers vim");
        item.put("body", "Use vim, not nano.");

        MemoryFile mem = BackgroundReviewer.toMemoryFile(item);
        assertNotNull(mem);
        assertEquals(MemoryFile.Type.USER, mem.getType(),
                "显式传 'user' 不应被 Reviewer 的 default FEEDBACK 覆盖");
    }

    @Test
    @DisplayName("toMemoryFile:缺 description → null(防写入垃圾 memory)")
    void to_memory_file_rejects_missing_field() {
        var item = new java.util.HashMap<String, Object>();
        item.put("name", "x");
        // 没 description,没 body
        assertNull(BackgroundReviewer.toMemoryFile(item),
                "缺必要字段必须返 null,不写垃圾 memory");
    }

    @Test
    @DisplayName("renderRecentDialogue 跳 tool_result 但保留 tool_use name(模式来源)")
    void dialogue_keeps_tool_use_drops_tool_result() {
        BackgroundReviewer reviewer = new BackgroundReviewer(
                freshStore(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "br-test-" + System.nanoTime())),
                null, null);

        List<LlmMessage> messages = List.of(
                userText("find foo"),
                assistantToolUse("bash"),
                userToolResult("tu_x", "very long output that should be skipped from review"),
                userText("now find bar")
        );

        String rendered = reviewer.renderRecentDialogue(messages);
        assertTrue(rendered.contains("[used tool: bash]"),
                "tool_use name 必须保留(模式来源,如 用 bash 还是用 read_file)");
        assertFalse(rendered.contains("very long output"),
                "tool_result 内容应该被跳过(太长 + 不是模式来源)");
        assertTrue(rendered.contains("find foo") && rendered.contains("now find bar"));
    }

    // ─────────────────────────────────────────────────────────────
    //  s21 Demo 27 / Hermes Tier 3 P3.2 — staged write_approval
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("writeApproval=true → 提案进 pendingStore,store 不写")
    void write_approval_routes_to_pending(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        PendingMemoryStore pending = new PendingMemoryStore(tempDir);
        String llmJson =
                "[{\"name\":\"feedback-prefer-ripgrep\"," +
                        "\"description\":\"User prefers ripgrep\"," +
                        "\"body\":\"Always rg.\"}]";
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(llmJson));
        BackgroundReviewer reviewer = new BackgroundReviewer(
                store, mock, "test-model", pending, true);

        int written = reviewer.review(longishConversation());

        assertEquals(1, written);
        // store 不应该有任何写入
        assertEquals(0, store.list().size(), "writeApproval=true 时正式 store 不该被写");
        // pending pool 应该有一条
        assertEquals(1, pending.count());
        var entry = pending.readAll().get(0);
        assertEquals("feedback-prefer-ripgrep", entry.getMemory().getName());
        assertEquals("reviewer", entry.getSource());
    }

    @Test
    @DisplayName("writeApproval=false → 直接写 store(Demo 26 等价行为)")
    void write_approval_disabled_writes_store_directly(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        PendingMemoryStore pending = new PendingMemoryStore(tempDir);
        String llmJson =
                "[{\"name\":\"feedback-x\"," +
                        "\"description\":\"x\"," +
                        "\"body\":\"y\"}]";
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(llmJson));
        // 5 参 ctor 但 writeApproval=false:即使 pendingStore 存在也不走 staged
        BackgroundReviewer reviewer = new BackgroundReviewer(
                store, mock, "test-model", pending, false);

        reviewer.review(longishConversation());
        assertEquals(1, store.list().size(), "writeApproval=false 时直接写 store");
        assertEquals(0, pending.count(), "pending pool 不该有内容");
    }

    @Test
    @DisplayName("3 参 ctor(老 Demo 26 兼容):pendingStore=null → 直接写 store")
    void legacy_ctor_writes_store_directly(@TempDir Path tempDir) {
        MemoryStore store = freshStore(tempDir);
        String llmJson =
                "[{\"name\":\"feedback-x\"," +
                        "\"description\":\"x\"," +
                        "\"body\":\"y\"}]";
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(llmJson));
        // 3 参 ctor,等价 (store, mock, model, null, false)
        BackgroundReviewer reviewer = new BackgroundReviewer(store, mock, "test-model");
        reviewer.review(longishConversation());
        assertEquals(1, store.list().size(), "3 参 ctor 应等价 Demo 26 行为(直接写 store)");
    }
}
