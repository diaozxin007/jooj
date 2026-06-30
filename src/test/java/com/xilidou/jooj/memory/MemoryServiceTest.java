package com.xilidou.jooj.memory;

import com.xilidou.jooj.http.AnthropicClient;
import com.xilidou.jooj.http.MockAnthropicClient;
import com.xilidou.jooj.http.ResponseFixtures;
import com.xilidou.jooj.http.dto.MessageParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link MemoryService} facade 的端到端行为。
 *
 * <p>这是 s09 集成层的测试,验证 4 个子系统通过 facade 协同工作:
 * <ol>
 *   <li>loadRelevant 调 Selector 渲染注入字符串</li>
 *   <li>onTurnEnd 先 extract 后 consolidate</li>
 *   <li>catalog 返回 readIndex 内容</li>
 *   <li>所有子系统失败时 facade 优雅降级</li>
 * </ol>
 */
class MemoryServiceTest {

    private static MemoryConfig configForDir(Path tempDir, int threshold) {
        return new MemoryConfig(tempDir, "MEMORY.md", 4096, threshold);
    }

    private static MessageParam userText(String text) {
        return MessageParam.user(text);
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 1:loadRelevant 端到端 — 写 memory + LLM 选 + 渲染
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loadRelevant: end-to-end LLM selection + rendering")
    void loadRelevant_e2e(@TempDir Path tempDir) {
        // LLM 第 1 次调用:Selector 选 [0]
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("[0]"));
        MemoryService service = new MemoryService(
                configForDir(tempDir, 100), mock, "test-model");
        // 预先写一条 memory(走 service.store() 包级访问)
        service.store().write(MemoryFile.of("user-tabs", MemoryFile.Type.USER,
                "User prefers tabs", "Use tabs not spaces."));

        String injection = service.loadRelevant(List.of(userText("about tabs")));

        assertTrue(injection.startsWith("<relevant_memories>"),
                "应渲染成 relevant_memories 块: " + injection);
        assertTrue(injection.contains("Use tabs not spaces."),
                "应包含选中 memory 的 body");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 2:loadRelevant 无相关 memory → 空字符串
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loadRelevant: no memories → empty string")
    void loadRelevant_no_memories(@TempDir Path tempDir) {
        MemoryService service = new MemoryService(
                configForDir(tempDir, 100), null, null);

        assertEquals("", service.loadRelevant(List.of(userText("anything"))));
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 3:onTurnEnd — 触发 extract(写入新 memory)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("onTurnEnd: triggers extract → writes new memories")
    void onTurnEnd_triggers_extract(@TempDir Path tempDir) {
        // Extractor LLM 返回 1 条新 memory
        // Consolidator 因文件数 < 阈值不会被触发
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(
                        "[{\"name\":\"new-fact\",\"type\":\"user\"," +
                                "\"description\":\"d\",\"body\":\"b\"}]"));
        MemoryService service = new MemoryService(
                configForDir(tempDir, 100), mock, "test-model");

        service.onTurnEnd(List.of(userText("I prefer tabs")));

        assertTrue(service.store().read("new-fact.md").isPresent(),
                "extract 应该写入了新 memory");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 4:onTurnEnd — 触发 consolidate(达阈值)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("onTurnEnd: triggers consolidate when count >= threshold")
    void onTurnEnd_triggers_consolidate(@TempDir Path tempDir) {
        // 阈值低 → 一开始 5 条已超阈值
        // 但 onTurnEnd 先跑 extract 再跑 consolidate, 都用 mock LLM
        // mock 提供 2 个响应:第 1 个 extract 返回 [], 第 2 个 consolidate 返回 1 条合并版
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("[]"),  // extract: 没新 fact
                ResponseFixtures.endTurn(        // consolidate: 5 → 1
                        "[{\"name\":\"merged\",\"type\":\"user\"," +
                                "\"description\":\"merged d\",\"body\":\"merged b\"}]"));

        MemoryService service = new MemoryService(
                configForDir(tempDir, 5), mock, "test-model");

        // 预先写 5 条 (= 阈值)
        for (int i = 0; i < 5; i++) {
            service.store().write(MemoryFile.of("mem-" + i, MemoryFile.Type.USER,
                    "d" + i, "b" + i));
        }

        service.onTurnEnd(List.of(userText("trigger consolidation")));

        // consolidate 应该被触发, 5 条变 1 条
        assertEquals(1, service.store().list().size(),
                "consolidate 后应剩 1 条");
        assertTrue(service.store().read("merged.md").isPresent());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 5:catalog — 返回 MEMORY.md 内容
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("catalog: returns rebuilt index")
    void catalog_returns_index(@TempDir Path tempDir) {
        MemoryService service = new MemoryService(
                configForDir(tempDir, 100), null, null);
        service.store().write(MemoryFile.of("foo", MemoryFile.Type.USER,
                "foo desc", "body"));
        service.store().write(MemoryFile.of("bar", MemoryFile.Type.PROJECT,
                "bar desc", "body"));

        String catalog = service.catalog();

        // catalog 是 MEMORY.md 的内容, 每行一条 - [name](filename) — desc
        assertTrue(catalog.contains("[foo]"));
        assertTrue(catalog.contains("[bar]"));
        assertTrue(catalog.contains("foo desc"));
        assertTrue(catalog.contains("bar desc"));
    }

    @Test
    @DisplayName("catalog: empty store → empty string")
    void catalog_empty(@TempDir Path tempDir) {
        MemoryService service = new MemoryService(
                configForDir(tempDir, 100), null, null);
        assertEquals("", service.catalog());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 6:子系统抛异常 → facade 优雅降级
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loadRelevant: subcomponent throws → returns empty, no propagation")
    void loadRelevant_graceful_on_failure(@TempDir Path tempDir) {
        // LLM 抛异常 → Selector fallback 到关键词 → 仍返回空(没匹配 keyword)
        AnthropicClient throwing = req -> {
            throw new RuntimeException("simulated LLM failure");
        };
        MemoryService service = new MemoryService(
                configForDir(tempDir, 100), throwing, "test-model");
        service.store().write(MemoryFile.of("foo", MemoryFile.Type.USER,
                "user prefers tabs", "body"));

        String injection = assertDoesNotThrow(
                () -> service.loadRelevant(List.of(userText("totally unrelated"))));

        // unrelated query 没关键词命中, 返回空(但不抛异常)
        assertEquals("", injection);
    }

    @Test
    @DisplayName("onTurnEnd: subcomponent throws → no propagation")
    void onTurnEnd_graceful_on_failure(@TempDir Path tempDir) {
        // Extractor 抛异常, Consolidator 也抛, facade 不该让它们传给上层
        AnthropicClient throwing = req -> {
            throw new RuntimeException("simulated LLM failure");
        };
        MemoryService service = new MemoryService(
                configForDir(tempDir, 5), throwing, "test-model");

        // 预存 5 条触发 consolidate
        for (int i = 0; i < 5; i++) {
            service.store().write(MemoryFile.of("mem-" + i, MemoryFile.Type.USER,
                    "d", "b"));
        }

        assertDoesNotThrow(() -> service.onTurnEnd(List.of(userText("test"))));

        // 5 条 memory 应保持原样(consolidate 失败 → 原子性不动磁盘)
        assertEquals(5, service.store().list().size());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 7:无 client(纯关键词回退路径)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no client: Selector keyword fallback works, Extractor/Consolidator disabled")
    void no_client_keyword_only_path(@TempDir Path tempDir) {
        MemoryService service = new MemoryService(
                configForDir(tempDir, 100), null, null);
        service.store().write(MemoryFile.of("user-tabs", MemoryFile.Type.USER,
                "User prefers tabs", "Use tabs not spaces."));

        // Selector 走关键词回退,"tabs" 能命中
        String injection = service.loadRelevant(List.of(userText("about tabs")));
        assertTrue(injection.contains("Use tabs not spaces."));

        // Extractor 禁用,onTurnEnd 不写新 memory
        int sizeBefore = service.store().list().size();
        service.onTurnEnd(List.of(userText("anything")));
        assertEquals(sizeBefore, service.store().list().size(),
                "无 client 时 Extractor 不应写入");
    }

    // ─────────────────────────────────────────────────────────────
    //  s21 Demo 21:catalogForSystemPrompt(P1.3)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("catalogForSystemPrompt should emit § format with quota header")
    void catalogForSystemPrompt_emits_section_separator_and_header(@TempDir Path tempDir) {
        // totalMaxBytes=200,让百分比可见(默认 20000 的话很少 memory 都是 0%)
        MemoryConfig config = new MemoryConfig(tempDir, "MEMORY.md", 4096, 10, 200);
        MemoryService service = new MemoryService(config, null, null);

        service.store().write(MemoryFile.of("user-tabs", MemoryFile.Type.USER,
                "User prefers tabs", "Use tabs not spaces."));      // body 20 char
        service.store().write(MemoryFile.of("project-x", MemoryFile.Type.PROJECT,
                "Project X uses Spring", "Project X uses Spring Boot 3."));  // body 29 char

        String catalog = service.catalogForSystemPrompt();
        // 顶部容量头(20+29=49 / 200 = 25%)
        assertTrue(catalog.startsWith("[Memory  49/200 chars (25%)]"),
                "catalog 应以容量头开始: " + catalog);

        // 每行 § 分隔 + name + filename + desc
        // s21 Demo 22:type 分组后 user / project 在不同 section
        assertTrue(catalog.contains("§ project-x (project-x.md) — Project X uses Spring"),
                "每行应是 § format with file: " + catalog);
        assertTrue(catalog.contains("§ user-tabs (user-tabs.md) — User prefers tabs"),
                "每行应是 § format with file: " + catalog);

        // 不应保留旧的 markdown 链接格式
        assertFalse(catalog.contains("- ["),
                "catalogForSystemPrompt 不应再使用 markdown 链接格式: " + catalog);
    }

    @Test
    @DisplayName("catalogForSystemPrompt should return empty string when no entries")
    void catalogForSystemPrompt_empty_when_no_entries(@TempDir Path tempDir) {
        MemoryService service = new MemoryService(
                new MemoryConfig(tempDir, "MEMORY.md", 4096, 10, 200),
                null, null);
        // 不写任何 entry
        assertEquals("", service.catalogForSystemPrompt(),
                "无 entry 时返回空字符串(让 SystemPromptAssembler 跳过整段 memory section)");
    }

    @Test
    @DisplayName("catalog (raw) should remain markdown link format for sidebar")
    void catalog_raw_unchanged_for_sidebar(@TempDir Path tempDir) {
        MemoryService service = new MemoryService(
                new MemoryConfig(tempDir, "MEMORY.md", 4096, 10, 200),
                null, null);
        service.store().write(MemoryFile.of("a", MemoryFile.Type.USER, "desc-a", "body"));

        String raw = service.catalog();
        assertTrue(raw.contains("- [a](a.md) — desc-a"),
                "raw catalog 应保留 markdown 链接格式给 SidebarController: " + raw);
        assertFalse(raw.contains("§"),
                "raw catalog 不应包含 § 分隔符: " + raw);
        assertFalse(raw.contains("[Memory"),
                "raw catalog 不应包含容量头: " + raw);
    }

    // ─────────────────────────────────────────────────────────────
    //  s21 Demo 22:catalogForSystemPrompt 按 type 分组(P2.3)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("catalogForSystemPrompt should group entries by type with semantic headers")
    void catalogForSystemPrompt_groups_by_type(@TempDir Path tempDir) {
        MemoryConfig config = new MemoryConfig(tempDir, "MEMORY.md", 4096, 10, 2000);
        MemoryService service = new MemoryService(config, null, null);

        service.store().write(MemoryFile.of("user-tabs", MemoryFile.Type.USER,
                "User prefers tabs", "Use tabs"));
        service.store().write(MemoryFile.of("feedback-no-mock-db", MemoryFile.Type.FEEDBACK,
                "Don't mock the database", "Use real db"));
        service.store().write(MemoryFile.of("project-x-uses-spring", MemoryFile.Type.PROJECT,
                "Project X uses Spring", "Spring Boot 3"));
        service.store().write(MemoryFile.of("linear-ingest-bugs", MemoryFile.Type.REFERENCE,
                "Pipeline bugs are in Linear INGEST", "Look there first"));

        String catalog = service.catalogForSystemPrompt();
        // 4 个 type 标题都出现,顺序固定:USER → FEEDBACK → PROJECT → REFERENCE
        int idxUser      = catalog.indexOf("User preferences:");
        int idxFeedback  = catalog.indexOf("Workflow lessons:");
        int idxProject   = catalog.indexOf("Project facts:");
        int idxReference = catalog.indexOf("Reference pointers:");
        assertTrue(idxUser > 0,      "应含 User preferences: " + catalog);
        assertTrue(idxFeedback > 0,  "应含 Workflow lessons: " + catalog);
        assertTrue(idxProject > 0,   "应含 Project facts: " + catalog);
        assertTrue(idxReference > 0, "应含 Reference pointers: " + catalog);
        assertTrue(idxUser < idxFeedback,    "顺序 USER < FEEDBACK");
        assertTrue(idxFeedback < idxProject, "顺序 FEEDBACK < PROJECT");
        assertTrue(idxProject < idxReference, "顺序 PROJECT < REFERENCE");
    }

    @Test
    @DisplayName("catalogForSystemPrompt should skip type headers with no entries")
    void catalogForSystemPrompt_skips_empty_type_groups(@TempDir Path tempDir) {
        MemoryConfig config = new MemoryConfig(tempDir, "MEMORY.md", 4096, 10, 2000);
        MemoryService service = new MemoryService(config, null, null);
        // 只写 USER + PROJECT
        service.store().write(MemoryFile.of("u1", MemoryFile.Type.USER, "user-1", "body"));
        service.store().write(MemoryFile.of("p1", MemoryFile.Type.PROJECT, "proj-1", "body"));

        String catalog = service.catalogForSystemPrompt();
        assertTrue(catalog.contains("User preferences:"),    "USER 段应出现");
        assertTrue(catalog.contains("Project facts:"),       "PROJECT 段应出现");
        assertFalse(catalog.contains("Workflow lessons:"),   "无 FEEDBACK entry → 段不该出现");
        assertFalse(catalog.contains("Reference pointers:"), "无 REFERENCE entry → 段不该出现");
    }

    @Test
    @DisplayName("typeHeader should map each type to a stable semantic label")
    void typeHeader_maps_correctly() {
        // 锁定标题文本不要被未来重构悄悄改变(LLM 行为依赖这些 keyword)
        assertEquals("User preferences:",   MemoryService.typeHeader(MemoryFile.Type.USER));
        assertEquals("Workflow lessons:",   MemoryService.typeHeader(MemoryFile.Type.FEEDBACK));
        assertEquals("Project facts:",      MemoryService.typeHeader(MemoryFile.Type.PROJECT));
        assertEquals("Reference pointers:", MemoryService.typeHeader(MemoryFile.Type.REFERENCE));
    }
}
