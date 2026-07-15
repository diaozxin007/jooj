package com.xilidou.jooj.search;

import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmThinking;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link SearchStore} 的核心行为(s21 Demo 25):
 *
 * <ul>
 *   <li>TextBlock / ToolResultBlock 索引;ToolUseBlock 不索引</li>
 *   <li>replaceSession 整盘覆盖语义</li>
 *   <li>跨 session 不串</li>
 *   <li>filter 组合(session_id / role / kind)</li>
 *   <li>中文按字切</li>
 *   <li>非法 FTS5 表达式不抛(返空 list)</li>
 *   <li>tool_name 跨 message 关联(ToolUseBlock 在 assistant,ToolResultBlock 在下一 user)</li>
 * </ul>
 *
 * <p>不走 Spring 容器 —— 直接 {@code new SearchStore(new SearchConfig(...))},
 * 跟 {@code SessionServiceTest} / {@code TasksToolTest} 同惯例。
 */
class SearchStoreTest {

    @TempDir
    Path tempDir;

    private SearchStore store;

    @BeforeEach
    void setUp() {
        SearchConfig config = new SearchConfig(
                tempDir.resolve("search.db"),
                1, 10, 50, 1000, "light"
        );
        store = new SearchStore(config);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    // ── 工具:构造 history ──

    private static LlmMessage userText(String text) {
        return LlmMessage.userText(text);
    }

    private static LlmMessage assistantBlocks(Object... blocks) {
        return LlmMessage.assistant(java.util.Arrays.stream(blocks)
                .map(b -> (LlmContent) b)
                .toList());
    }

    @Test
    @DisplayName("TextBlock 进 token 列,replaceSession 后能搜到")
    void index_text_block() {
        List<LlmMessage> hist = List.of(
                userText("hello world"),
                assistantBlocks(new LlmText("I will help with weixin integration"))
        );
        store.replaceSession("s1", hist, Instant.now());

        List<SearchHit> hits = store.search(SearchQuery.of("weixin", 5));
        assertEquals(1, hits.size());
        SearchHit h = hits.get(0);
        assertEquals("s1", h.sessionId());
        assertEquals("assistant", h.role());
        assertEquals("text", h.kind());
        assertNotNull(h.snippet());
        assertTrue(h.snippet().contains("<b>weixin</b>"),
                "snippet should highlight match: " + h.snippet());
    }

    @Test
    @DisplayName("ToolResultBlock 索引为 kind=tool_result + 关联 tool_use_id")
    void index_tool_result_block() {
        // 先 assistant 调 tool_use,再 user 给 tool_result
        LlmToolCall tu = new LlmToolCall("toolu_001", "bash", null);
        LlmToolResult tr = LlmToolResult.success("toolu_001", "ls -la output here");

        List<LlmMessage> hist = List.of(
                assistantBlocks(new LlmText("running ls"), tu),
                LlmMessage.toolResults(List.of(tr))
        );
        store.replaceSession("s1", hist, Instant.now());

        List<SearchHit> hits = store.search(SearchQuery.of("ls", 5));
        // 命中 2 条:assistant 文本 "running ls" + tool_result "ls -la output here"
        assertEquals(2, hits.size());

        SearchHit toolResultHit = hits.stream()
                .filter(h -> "tool_result".equals(h.kind()))
                .findFirst()
                .orElseThrow();
        assertEquals("tool", toolResultHit.role(),
                "canonical: tool_result 在 TOOL role 消息里(不再是 pre-P2 的 role=user)");
        assertEquals("toolu_001", toolResultHit.toolUseId());
        assertEquals("bash", toolResultHit.toolName(), "tool_name 应跨 message 关联到 ToolUseBlock.name");
    }

    @Test
    @DisplayName("ToolUseBlock 不进 token 列,搜 tool_use 的 input 找不到")
    void tool_use_not_indexed() {
        LlmToolCall tu = new LlmToolCall("toolu_002", "bash", null);
        // LlmToolCall.input 是 JsonNode,不会进 FTS5;name 也不进 token 列(只进 tool_name UNINDEXED)
        List<LlmMessage> hist = List.of(assistantBlocks(tu));
        store.replaceSession("s1", hist, Instant.now());

        // FTS 表里这条 session 应该 0 行(ToolUseBlock 跳过,没 TextBlock)
        assertEquals(0, store.countSession("s1"));
    }

    @Test
    @DisplayName("replaceSession 第二次写覆盖第一次(整盘语义)")
    void replace_session_overwrites() {
        store.replaceSession("s1", List.of(userText("alpha bravo")), Instant.now());
        assertEquals(1, store.countSession("s1"));

        store.replaceSession("s1", List.of(userText("charlie delta")), Instant.now());
        assertEquals(1, store.countSession("s1"));

        // alpha 已被覆盖删除
        assertTrue(store.search(SearchQuery.of("alpha", 5)).isEmpty());
        assertEquals(1, store.search(SearchQuery.of("charlie", 5)).size());
    }

    @Test
    @DisplayName("跨 session 不串味:session_id filter 锁定一条")
    void cross_session_isolation() {
        store.replaceSession("s1", List.of(userText("apple in s1")), Instant.now());
        store.replaceSession("s2", List.of(userText("apple in s2")), Instant.now());

        // 跨 session 搜:都搜到
        assertEquals(2, store.search(SearchQuery.of("apple", 10)).size());

        // 锁 s1:只 1 条
        SearchQuery q = new SearchQuery("apple", "s1", null, null, 10);
        List<SearchHit> only_s1 = store.search(q);
        assertEquals(1, only_s1.size());
        assertEquals("s1", only_s1.get(0).sessionId());
    }

    @Test
    @DisplayName("role / kind filter 组合都 work")
    void filter_role_and_kind() {
        LlmToolCall tu = new LlmToolCall("toolu_003", "bash", null);
        LlmToolResult tr = LlmToolResult.success("toolu_003", "shared keyword here");

        List<LlmMessage> hist = List.of(
                userText("shared keyword from user"),
                assistantBlocks(new LlmText("shared keyword from assistant"), tu),
                LlmMessage.toolResults(List.of(tr))
        );
        store.replaceSession("s1", hist, Instant.now());

        // 不 filter:3 条命中
        assertEquals(3, store.search(SearchQuery.of("keyword", 10)).size());

        // role=user + kind=text:1 条(用户文本)
        List<SearchHit> userOnly = store.search(new SearchQuery("keyword", null, "user", "text", 10));
        assertEquals(1, userOnly.size());
        assertEquals("user", userOnly.get(0).role());
        assertEquals("text", userOnly.get(0).kind());

        // kind=tool_result:1 条
        List<SearchHit> toolOnly = store.search(new SearchQuery("keyword", null, null, "tool_result", 10));
        assertEquals(1, toolOnly.size());
        assertEquals("tool_result", toolOnly.get(0).kind());
    }

    @Test
    @DisplayName("中文按字切(unicode61):搜单字也命中")
    void chinese_unicode_tokenize() {
        store.replaceSession("s1", List.of(userText("微信改造日志")), Instant.now());

        // unicode61 把每个中文字当 token
        List<SearchHit> hits = store.search(SearchQuery.of("微信", 5));
        assertEquals(1, hits.size());
        assertNotNull(hits.get(0).snippet());
    }

    @Test
    @DisplayName("非法 FTS5 表达式不抛,返空 list")
    void invalid_fts5_query_does_not_throw() {
        store.replaceSession("s1", List.of(userText("hello world")), Instant.now());

        // 未配对的引号 / 单独的 NEAR 等会让 FTS5 抛 SQLException
        assertDoesNotThrow(() -> {
            List<SearchHit> hits = store.search(SearchQuery.of("\"unbalanced", 5));
            assertNotNull(hits);
            // 期望返空 list(SearchStore 内部 catch SQLException)
            assertTrue(hits.isEmpty(), "应吞掉非法表达式异常返空");
        });
    }

    @Test
    @DisplayName("tool_name 跨 message 关联(ToolUse 在 assistant,ToolResult 在下一 user message)")
    void tool_name_cross_message_link() {
        // 第一 message: assistant + ToolUse(name=bash)
        // 第二 message: user + ToolResult
        // 第三 message: assistant + ToolUse(name=read_file)
        // 第四 message: user + ToolResult
        LlmToolCall tuBash = new LlmToolCall("u1", "bash", null);
        LlmToolResult trBash = LlmToolResult.success("u1", "result from bash");
        LlmToolCall tuRead = new LlmToolCall("u2", "read_file", null);
        LlmToolResult trRead = LlmToolResult.success("u2", "result from read_file");

        List<LlmMessage> hist = List.of(
                assistantBlocks(tuBash),
                LlmMessage.toolResults(List.of(trBash)),
                assistantBlocks(tuRead),
                LlmMessage.toolResults(List.of(trRead))
        );
        store.replaceSession("s1", hist, Instant.now());

        // 搜 "result":2 条命中,各自 tool_name 不同
        List<SearchHit> hits = store.search(SearchQuery.of("result", 10));
        assertEquals(2, hits.size());

        SearchHit bashHit = hits.stream().filter(h -> "u1".equals(h.toolUseId())).findFirst().orElseThrow();
        assertEquals("bash", bashHit.toolName());

        SearchHit readHit = hits.stream().filter(h -> "u2".equals(h.toolUseId())).findFirst().orElseThrow();
        assertEquals("read_file", readHit.toolName());
    }

    @Test
    @DisplayName("LlmThinking / LlmOpaque 都跳过不抛")
    void thinking_and_unknown_blocks_skipped() {
        // 注意:LlmThinking 和 LlmOpaque(未知类型)都不该抛
        List<LlmMessage> hist = List.of(
                assistantBlocks(
                        new LlmThinking("internal reasoning", "sig_xxx", "anthropic"),
                        new LlmText("after thinking, here's text")
                ),
                // canonical 里 tool_result output 是 String;这里空字符串 → 跳过
                LlmMessage.toolResults(List.of(
                        LlmToolResult.success("u_unknown", "")))
        );
        assertDoesNotThrow(() -> store.replaceSession("s1", hist, Instant.now()));

        // 只有 1 条 LlmText 命中(thinking + empty tool_result 都被跳过)
        assertEquals(1, store.countSession("s1"));
        List<SearchHit> hits = store.search(SearchQuery.of("thinking", 5));
        assertEquals(1, hits.size());
    }

    @Test
    @DisplayName("deleteSession 删干净")
    void delete_session_clears_rows() {
        store.replaceSession("s1", List.of(userText("alpha")), Instant.now());
        store.replaceSession("s2", List.of(userText("alpha")), Instant.now());
        assertEquals(2, store.countAll());

        store.deleteSession("s1");
        assertEquals(1, store.countAll());
        assertEquals(0, store.countSession("s1"));
        assertEquals(1, store.countSession("s2"));
    }

    @Test
    @DisplayName("陌生 content shape 不抛(防御性跳过)")
    void weird_content_type_skipped() {
        // canonical 里 LlmMessage.content 一定是 List<LlmContent>,没有 String/Map 分支;
        // 陌生 content 只能通过 LlmOpaque 出现,SearchStore 应跳过不抛
        List<LlmMessage> hist = List.of(
                LlmMessage.assistant(List.of(new com.xilidou.jooj.llm.domain.LlmOpaque(
                        "weird", "type", java.util.Map.of("k", "v")))),
                LlmMessage.userText("valid text")
        );
        assertDoesNotThrow(() -> store.replaceSession("s1", hist, Instant.now()));
        // 只 1 条索引(陌生 Opaque 跳过,LlmText 索引)
        assertEquals(1, store.countSession("s1"));
    }
}
