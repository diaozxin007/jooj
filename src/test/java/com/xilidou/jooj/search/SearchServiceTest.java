package com.xilidou.jooj.search;

import com.xilidou.jooj.llm.domain.LlmMessage;
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
 * 锁定 {@link SearchService} 的关键不变量(s21 Demo 25):
 *
 * <ul>
 *   <li>onSaveHistory 失败不抛(JSON 主流程不能被挡)</li>
 *   <li>limit clamp 到 maxLimit</li>
 *   <li>onClearHistory / onDeleteSession 真删除行</li>
 * </ul>
 */
class SearchServiceTest {

    @TempDir
    Path tempDir;

    private SearchStore store;
    private SearchService service;
    private SearchConfig config;

    @BeforeEach
    void setUp() {
        config = new SearchConfig(
                tempDir.resolve("search.db"),
                1, 10, 50, 1000, "light"
        );
        store = new SearchStore(config);
        service = new SearchService(store, config);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    @DisplayName("onSaveHistory 即使 store 抛也不抛出 —— JSON 主流程不能被挡")
    void on_save_history_swallows_exceptions() {
        // 关掉 store,任何调用都会抛 SQLException
        store.close();

        // 不应抛任何异常
        assertDoesNotThrow(() -> service.onSaveHistory("s1",
                List.of(LlmMessage.userText("hello"))));
        assertDoesNotThrow(() -> service.onDeleteSession("s1"));
        assertDoesNotThrow(() -> service.onClearHistory("s1"));
    }

    @Test
    @DisplayName("search 把 limit clamp 到 maxLimit")
    void search_clamps_limit() {
        // 写 60 行
        var msgs = new java.util.ArrayList<LlmMessage>();
        for (int i = 0; i < 60; i++) {
            msgs.add(LlmMessage.userText("token" + " match" + i));
        }
        service.onSaveHistory("s1", msgs);

        // 请求 limit=999,maxLimit=50,实际应返 ≤50
        SearchQuery q = new SearchQuery("match", null, null, null, 999);
        List<SearchHit> hits = service.search(q);
        assertTrue(hits.size() <= 50, "limit 应被 clamp 到 maxLimit=50,实际:" + hits.size());
    }

    @Test
    @DisplayName("onClearHistory 后搜不到内容")
    void on_clear_history_removes_rows() {
        service.onSaveHistory("s1",
                List.of(LlmMessage.userText("ghost content here")));
        assertEquals(1, service.search(SearchQuery.of("ghost", 5)).size());

        service.onClearHistory("s1");
        assertEquals(0, service.search(SearchQuery.of("ghost", 5)).size(),
                "clearHistory 后必须清掉,否则 LLM 搜出'幽灵'内容");
    }

    @Test
    @DisplayName("defaultLimit / maxLimit 暴露给 Tool 层")
    void exposes_limit_config() {
        assertEquals(10, service.defaultLimit());
        assertEquals(50, service.maxLimit());
    }
}
