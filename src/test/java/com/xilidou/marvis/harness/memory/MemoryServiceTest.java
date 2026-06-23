package com.xilidou.marvis.harness.memory;

import com.xilidou.marvis.harness.http.AnthropicClient;
import com.xilidou.marvis.harness.http.MockAnthropicClient;
import com.xilidou.marvis.harness.http.ResponseFixtures;
import com.xilidou.marvis.harness.http.dto.MessageParam;
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
}
