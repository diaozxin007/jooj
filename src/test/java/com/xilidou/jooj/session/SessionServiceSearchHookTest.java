package com.xilidou.jooj.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.search.SearchConfig;
import com.xilidou.jooj.search.SearchHit;
import com.xilidou.jooj.search.SearchQuery;
import com.xilidou.jooj.search.SearchService;
import com.xilidou.jooj.search.SearchStore;
import com.xilidou.jooj.transcript.SessionDeleted;
import com.xilidou.jooj.transcript.UserMessageReceived;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 SessionService ↔ SearchService 集成行为。
 *
 * <h3>s22 P3-b 语义演变</h3>
 *
 * <p>Demo 25 时代,SearchService 索引由 {@link SessionService#saveHistory} 尾部
 * {@code onSaveHistory(sid, history)} 钩子驱动 —— 但这个钩子看到的是**被 memory
 * injection 污染**的 history({@code <memories>...</memories>\n\n} 前缀混在
 * user content 里),搜索会命中污染内容,用户搜自己敲过的原文反而搜不到。
 *
 * <p>s22 P3-b 改成事件驱动:{@link com.xilidou.jooj.transcript.TranscriptEvent}
 * 由 AgentLoopHarness 在 memory injection **之前**发布,SearchService 作为
 * {@link org.springframework.context.event.EventListener} 直接从事件流拿干净原文
 * incremental append 到 FTS。SessionService.saveHistory 不再自动写索引。
 *
 * <h3>本类测试范围</h3>
 *
 * <ul>
 *   <li>事件驱动索引 —— 触发 {@link SearchService#onUserMessage} 后能搜到</li>
 *   <li>{@link SessionService#delete} → {@link SearchService#onDeleteSession} 兜底清索引</li>
 *   <li>{@link SessionService#clearHistory} → {@link SearchService#onClearHistory} 清索引</li>
 *   <li>SearchService 故障不挡 SessionService JSON 主流程</li>
 *   <li>1 参 ctor(不接 SearchService)老路径不退化</li>
 * </ul>
 *
 * <p>直接调 listener 方法(而非通过 Spring publishEvent)—— 本类是**方法级**契约测试;
 * 端到端事件流转的 Spring 装配验证在 {@link com.xilidou.jooj.transcript.TranscriptSpringIT}
 * 和 {@link com.xilidou.jooj.agent.AgentLoopHarnessTranscriptIT} 里覆盖。
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

        service = SessionService.forTests(store, searchService);
        service.ensureBootstrap();
    }

    @AfterEach
    void tearDown() {
        if (searchStore != null) searchStore.close();
    }

    // ── 事件驱动索引(s22 P3-b 新语义)─────────────────────────

    @Test
    @DisplayName("UserMessageReceived 事件触发后立即可搜到(P3-b 事件驱动)")
    void user_message_event_writes_to_search_index() {
        Session s = service.create("test");
        // s22 P3-b:模拟 AgentLoopHarness 在 processOneQuery 入门发的事件。
        // 干净原文直接进 FTS,不带任何 memory prefix 污染。
        searchService.onUserMessage(new UserMessageReceived(
                UUID.randomUUID(), s.id(),
                "discussing weixin integration today",
                Instant.now(), "session"));

        List<SearchHit> hits = searchService.search(SearchQuery.of("weixin", 5));
        assertEquals(1, hits.size(),
                "P3-b: 事件驱动索引 —— UserMessageReceived 到就 append 到 FTS");
        assertEquals(s.id(), hits.get(0).sessionId());
    }

    @Test
    @DisplayName("saveHistory 不再触发索引(P3-b:事件驱动后旧钩子已断)")
    void save_history_no_longer_auto_indexes() {
        Session s = service.create("no-hook");
        List<LlmMessage> hist = service.loadHistory(s.id());
        hist.add(LlmMessage.userText("this bypasses events"));
        // 关键:直接调 saveHistory,不发事件。P3-b 之前会自动索引,现在不会。
        service.saveHistory(s.id(), hist);

        // P3-b 语义:saveHistory 只写 JSON,不再自动写 FTS
        assertEquals(0,
                searchService.search(SearchQuery.of("bypasses", 5)).size(),
                "saveHistory 单独不再触发 FTS 索引 —— 索引由 event listener 负责");

        // 但 JSON 仍应写入(sessionService 的 core 职责没变)
        assertEquals(1, service.loadHistory(s.id()).size(),
                "JSON 主流程仍工作:history 已落盘");
    }

    // ── 生命周期钩子 —— delete / clearHistory ─────────────────

    @Test
    @DisplayName("delete 后搜不到 —— 由 onDeleteSession 兜底")
    void delete_session_clears_search_index() {
        Session s = service.create("temp");
        // 先通过事件把消息进 FTS(模拟正常 turn 之后的状态)
        searchService.onUserMessage(new UserMessageReceived(
                UUID.randomUUID(), s.id(), "ghost message",
                Instant.now(), "session"));
        assertEquals(1, searchService.search(SearchQuery.of("ghost", 5)).size(),
                "前置条件:事件已把消息进 FTS");

        // SessionService.delete 内部会调 searchService.onDeleteSession(id)兜底
        service.delete(s.id());
        assertEquals(0, searchService.search(SearchQuery.of("ghost", 5)).size(),
                "delete 后必须从 search index 清掉");
    }

    @Test
    @DisplayName("clearHistory 后搜不到 —— 由 onClearHistory 兜底")
    void clear_history_clears_search_index() {
        Session s = service.create("c");
        searchService.onUserMessage(new UserMessageReceived(
                UUID.randomUUID(), s.id(), "ephemeral phrase",
                Instant.now(), "session"));
        assertEquals(1, searchService.search(SearchQuery.of("ephemeral", 5)).size(),
                "前置条件:事件已把消息进 FTS");

        // s22 P3-b clearHistory 补加了 searchService.onClearHistory 调用
        service.clearHistory(s.id());
        assertEquals(0, searchService.search(SearchQuery.of("ephemeral", 5)).size(),
                "clearHistory 后 search 应清空");
    }

    @Test
    @DisplayName("SessionDeleted 事件也能清索引(D6 语义)")
    void session_deleted_event_also_clears_index() {
        Session s = service.create("via-event");
        searchService.onUserMessage(new UserMessageReceived(
                UUID.randomUUID(), s.id(), "target for deletion",
                Instant.now(), "session"));
        assertEquals(1, searchService.search(SearchQuery.of("deletion", 5)).size());

        // 直接调 event listener(不走 SessionService.delete)
        searchService.onSessionDeleted(new SessionDeleted(
                UUID.randomUUID(), s.id(), Instant.now()));

        assertEquals(0, searchService.search(SearchQuery.of("deletion", 5)).size(),
                "SessionDeleted event listener 也应清索引");
    }

    // ── 韧性 —— SearchService 故障不挡 JSON 主流程 ────────────

    @Test
    @DisplayName("SearchService 抛(close 后)仍允许 JSON 写成功")
    void search_failure_does_not_block_json_write() {
        Session s = service.create("resilient");
        // 模拟 SearchStore 故障:关掉它
        searchStore.close();

        List<LlmMessage> hist = service.loadHistory(s.id());
        hist.add(LlmMessage.userText("must persist to JSON regardless"));
        // s22 P3-b 后 saveHistory 不再调 searchService,更不会因为它出错。
        // 但保留这个测试锁定"JSON 主流程独立可用"这个更根本的属性。
        assertDoesNotThrow(() -> service.saveHistory(s.id(), hist));

        // JSON 文件应已写入(SessionStore 正常)
        Path jsonPath = tmp.resolve("sessions").resolve(s.id() + ".json");
        assertTrue(java.nio.file.Files.exists(jsonPath),
                "JSON 应该被写入");
    }

    @Test
    @DisplayName("SessionService 1 参 ctor 仍合法 —— 测试老路径不退化")
    void single_arg_ctor_still_works() {
        SessionService legacy = SessionService.forTests(store);
        legacy.ensureBootstrap();
        Session s = legacy.create("legacy");
        List<LlmMessage> hist = legacy.loadHistory(s.id());
        hist.add(LlmMessage.userText("legacy path"));
        // 不应抛 NPE
        assertDoesNotThrow(() -> legacy.saveHistory(s.id(), hist));
    }
}
