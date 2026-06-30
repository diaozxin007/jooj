package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.search.SearchConfig;
import com.xilidou.jooj.search.SearchService;
import com.xilidou.jooj.search.SearchStore;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link SessionSearchTool} 的派发与输出格式(s21 Demo 25):
 *
 * <ul>
 *   <li>execute 空结果 → "No hits."</li>
 *   <li>有结果 → 紧凑文本格式</li>
 *   <li>缺 query 参数 → friendly 错误</li>
 * </ul>
 *
 * <p>不走 Spring,直接 {@code new SessionSearchTool(...)}。
 */
class SessionSearchToolTest {

    @TempDir
    Path tempDir;

    private SearchStore store;
    private SearchService service;
    private SessionSearchTool tool;

    @BeforeEach
    void setUp() {
        SearchConfig config = new SearchConfig(
                tempDir.resolve("search.db"),
                1, 10, 50, 1000, "light"
        );
        store = new SearchStore(config);
        service = new SearchService(store, config);
        tool = new SessionSearchTool(service);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    private ToolResult call(Map<String, Object> args) {
        return tool.execute(new ToolCall("session_search", args));
    }

    @Test
    @DisplayName("getTools 返回 1 个 ToolDefinition,name=session_search")
    void exposes_one_tool_definition() {
        List<ToolDefinition> defs = tool.getTools();
        assertEquals(1, defs.size());
        assertEquals("session_search", defs.get(0).getName());
    }

    @Test
    @DisplayName("空索引时搜任何东西都返 No hits.")
    void empty_index_returns_no_hits() {
        ToolResult r = call(Map.of("query", "anything"));
        assertTrue(r.isSuccess());
        assertEquals("No hits.", r.getOutput());
    }

    @Test
    @DisplayName("有结果时输出紧凑文本格式 #N [session=...|role|msg=...] snippet")
    void formatted_output_for_hits() {
        // 写一些 history 进 search index
        service.onSaveHistory("session_abc",
                List.of(MessageParam.user("we discussed weixin integration last week")));

        ToolResult r = call(Map.of("query", "weixin"));
        assertTrue(r.isSuccess());
        String out = r.getOutput();
        assertNotNull(out);
        assertTrue(out.startsWith("#1 "), "应以 #1 开头:\n" + out);
        assertTrue(out.contains("[session=session_abc"), "应含 session 头:\n" + out);
        assertTrue(out.contains("|user|"), "应含 role:\n" + out);
        assertTrue(out.contains("<b>weixin</b>"), "应含 highlight snippet:\n" + out);
    }

    @Test
    @DisplayName("缺 query 参数 → success=false + friendly 错误")
    void missing_query_arg_returns_error() {
        ToolResult r = call(Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getOutput().contains("query"));
    }

    @Test
    @DisplayName("空 query string → No hits.(不走 SQL)")
    void empty_query_returns_no_hits() {
        ToolResult r = call(Map.of("query", "   "));
        assertTrue(r.isSuccess());
        assertEquals("No hits.", r.getOutput());
    }

    @Test
    @DisplayName("limit 参数解析:数字字符串与 Number 都接受;非数字给 error")
    void limit_arg_parsing() {
        service.onSaveHistory("s1", List.of(MessageParam.user("alpha bravo charlie")));

        // 整数
        assertTrue(call(Map.of("query", "alpha", "limit", 5)).isSuccess());
        // 字符串数字
        assertTrue(call(Map.of("query", "alpha", "limit", "3")).isSuccess());
        // 非法数字
        ToolResult bad = call(Map.of("query", "alpha", "limit", "abc"));
        assertFalse(bad.isSuccess());
        assertTrue(bad.getOutput().contains("limit"));
    }

    @Test
    @DisplayName("session_id filter 锁定一条 session")
    void session_id_filter() {
        service.onSaveHistory("s1", List.of(MessageParam.user("apple in s1")));
        service.onSaveHistory("s2", List.of(MessageParam.user("apple in s2")));

        ToolResult r = call(Map.of("query", "apple", "session_id", "s1"));
        assertTrue(r.isSuccess());
        // 只包含 s1 的命中
        assertTrue(r.getOutput().contains("session=s1"));
        assertFalse(r.getOutput().contains("session=s2"));
    }
}
