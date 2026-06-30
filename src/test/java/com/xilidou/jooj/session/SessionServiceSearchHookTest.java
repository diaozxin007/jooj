package com.xilidou.jooj.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.search.SearchConfig;
import com.xilidou.jooj.search.SearchHit;
import com.xilidou.jooj.search.SearchQuery;
import com.xilidou.jooj.search.SearchService;
import com.xilidou.jooj.search.SearchStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 SessionService ↔ SearchService 钩子集成行为(s21 Demo 25):
 *
 * <ul>
 *   <li>saveHistory 后能立即搜到</li>
 *   <li>delete 后搜不到</li>
 *   <li>clearHistory 后搜不到</li>
 *   <li>SearchService 抛仍 JSON 写成功(失败不挡主流程)</li>
 * </ul>
 */
class SessionServiceSearchHookTest {

    @TempDir
    Path tmp;

    SessionStore store;
    SearchStore searchStore;
    SearchService searchService;
    SessionService service;

    @BeforeEach
    void setUp() throws IOException {
        Path sessionsDir = tmp.resolve("sessions");
        java.nio.file.Files.createDirectories(sessionsDir);
        store = new SessionStore(sessionsDir,
                new ObjectMapper().findAndRegisterModules());

        SearchConfig sc = new SearchConfig(
                tmp.resolve("search.db"),
                1, 10, 50, 1000, "light"
        );
        searchStore = new SearchStore(sc);
        searchService = new SearchService(searchStore, sc);

        service = new SessionService(store, searchService);
        service.ensureBootstrap();
    }

    @AfterEach
    void tearDown() {
        if (searchStore != null) searchStore.close();
    }

    @Test
    @DisplayName("saveHistory 后立即可搜到 —— 同步双写")
    void save_history_writes_to_search_index() {
        Session s = service.create("test");
        List<MessageParam> hist = service.loadHistory(s.id());
        hist.add(MessageParam.user("discussing weixin integration today"));
        service.saveHistory(s.id(), hist);

        List<SearchHit> hits = searchService.search(SearchQuery.of("weixin", 5));
        assertEquals(1, hits.size());
        assertEquals(s.id(), hits.get(0).sessionId());
    }

    @Test
    @DisplayName("delete 后搜不到")
    void delete_session_clears_search_index() {
        Session s = service.create("temp");
        List<MessageParam> hist = service.loadHistory(s.id());
        hist.add(MessageParam.user("ghost message"));
        service.saveHistory(s.id(), hist);
        assertEquals(1, searchService.search(SearchQuery.of("ghost", 5)).size());

        service.delete(s.id());
        assertEquals(0, searchService.search(SearchQuery.of("ghost", 5)).size(),
                "delete 后必须从 search index 清掉");
    }

    @Test
    @DisplayName("clearHistory 后搜不到")
    void clear_history_clears_search_index() {
        Session s = service.create("c");
        List<MessageParam> hist = service.loadHistory(s.id());
        hist.add(MessageParam.user("ephemeral phrase"));
        service.saveHistory(s.id(), hist);
        assertEquals(1, searchService.search(SearchQuery.of("ephemeral", 5)).size());

        service.clearHistory(s.id());
        assertEquals(0, searchService.search(SearchQuery.of("ephemeral", 5)).size(),
                "clearHistory 后 search 应清空");
    }

    @Test
    @DisplayName("SearchService 抛(close 后)仍允许 JSON 写成功")
    void search_failure_does_not_block_json_write() {
        Session s = service.create("resilient");
        // 模拟 SearchStore 故障:关掉它
        searchStore.close();

        List<MessageParam> hist = service.loadHistory(s.id());
        hist.add(MessageParam.user("must persist to JSON regardless"));
        // SearchService.onSaveHistory 内部 catch Throwable warn,不抛
        assertDoesNotThrow(() -> service.saveHistory(s.id(), hist));

        // JSON 文件应已写入(SessionStore 正常)
        Path jsonPath = tmp.resolve("sessions").resolve(s.id() + ".json");
        assertTrue(java.nio.file.Files.exists(jsonPath),
                "SearchService 失败,JSON 仍应该被写入");
    }

    @Test
    @DisplayName("SessionService 1 参 ctor 仍合法 —— 测试老路径不退化")
    void single_arg_ctor_still_works() {
        SessionService legacy = new SessionService(store);
        legacy.ensureBootstrap();
        Session s = legacy.create("legacy");
        List<MessageParam> hist = legacy.loadHistory(s.id());
        hist.add(MessageParam.user("legacy path"));
        // 不应抛 NPE
        assertDoesNotThrow(() -> legacy.saveHistory(s.id(), hist));
    }
}