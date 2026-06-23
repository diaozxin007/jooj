package com.xilidou.marvis.harness.memory;

import com.xilidou.marvis.harness.http.AnthropicClient;
import com.xilidou.marvis.harness.http.MockAnthropicClient;
import com.xilidou.marvis.harness.http.ResponseFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link MemoryConsolidator} 的核心行为。
 *
 * <p>9 个关键场景:
 * <ol>
 *   <li>memory 数 < 阈值 → 不动</li>
 *   <li>正常合并: 5 条变 2 条 → 旧的删, 新的写</li>
 *   <li>无 client → 直接返回 0</li>
 *   <li>LLM 抛异常 → 不动磁盘(原子性)</li>
 *   <li>LLM 返回坏 JSON → 不动磁盘(原子性)</li>
 *   <li>LLM 返回 [] / 全 invalid → 不动磁盘(安全闸门)</li>
 *   <li>同 name 在结果里 → 覆盖原有内容(更新语义)</li>
 *   <li>新结果含全新 name → 旧的删 + 新的写(替换语义)</li>
 *   <li>原子性:中途断 LLM 调用旧文件保持不变</li>
 * </ol>
 */
class MemoryConsolidatorTest {

    /** 阈值 5 的小配置, 测试更敏感。*/
    private static MemoryConfig configForDir(Path tempDir, int threshold) {
        return new MemoryConfig(tempDir, "MEMORY.md", 4096, threshold);
    }

    private static MemoryStore freshStore(Path tempDir, int threshold) {
        return new MemoryStore(configForDir(tempDir, threshold));
    }

    /** 写 N 条样本 memory, 名字按 i 递增。*/
    private static void seedMemories(MemoryStore store, int count) {
        for (int i = 0; i < count; i++) {
            store.write(MemoryFile.of("mem-" + i, MemoryFile.Type.USER,
                    "desc " + i, "body " + i));
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 1: 不到阈值, 不动
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("count < threshold → no consolidation")
    void below_threshold_skipped(@TempDir Path tempDir) {
        MemoryConfig cfg = configForDir(tempDir, 10);
        MemoryStore store = new MemoryStore(cfg);
        seedMemories(store, 5);  // 5 < 10

        // mock 不该被调用
        AnthropicClient mock = MockAnthropicClient.ofResponses();
        MemoryConsolidator consolidator = new MemoryConsolidator(store, cfg, mock, "test-model");

        int result = consolidator.consolidate();

        assertEquals(0, result);
        assertEquals(5, store.list().size(), "应保持 5 条不动");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 2: 正常合并 — 5 条变 2 条
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("normal consolidation: LLM merges 5 → 2, deletes old, writes new")
    void normal_consolidation(@TempDir Path tempDir) {
        MemoryConfig cfg = configForDir(tempDir, 5);
        MemoryStore store = new MemoryStore(cfg);
        seedMemories(store, 5);  // mem-0 ~ mem-4

        // LLM 返回 2 条新的 (没有 mem-* 前缀, 完全替换)
        String llmJson = "[" +
                "{\"name\":\"merged-prefs\",\"type\":\"user\"," +
                "\"description\":\"Merged user prefs\",\"body\":\"All user preferences\"}," +
                "{\"name\":\"merged-feedback\",\"type\":\"feedback\"," +
                "\"description\":\"Merged feedback\",\"body\":\"All feedback consolidated\"}" +
                "]";
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(llmJson));
        MemoryConsolidator consolidator = new MemoryConsolidator(store, cfg, mock, "test-model");

        int result = consolidator.consolidate();

        assertEquals(2, result);
        // 旧的 mem-0 ~ mem-4 应该全被删
        for (int i = 0; i < 5; i++) {
            assertTrue(store.read("mem-" + i + ".md").isEmpty(),
                    "old mem-" + i + ".md should be deleted");
        }
        // 新的 2 条应该存在
        assertTrue(store.read("merged-prefs.md").isPresent());
        assertTrue(store.read("merged-feedback.md").isPresent());
        assertEquals(2, store.list().size());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 3: 无 client → 直接返回 0
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("client=null → returns 0, originals preserved")
    void no_client(@TempDir Path tempDir) {
        MemoryConfig cfg = configForDir(tempDir, 3);
        MemoryStore store = new MemoryStore(cfg);
        seedMemories(store, 5);

        MemoryConsolidator consolidator = new MemoryConsolidator(store, cfg, null, null);

        int result = consolidator.consolidate();

        assertEquals(0, result);
        assertEquals(5, store.list().size(), "应保持 5 条不动");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 4: LLM 抛异常 → 旧文件保持不变(原子性)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM throws → originals preserved (atomicity)")
    void llm_failure_preserves_originals(@TempDir Path tempDir) {
        MemoryConfig cfg = configForDir(tempDir, 3);
        MemoryStore store = new MemoryStore(cfg);
        seedMemories(store, 5);

        AnthropicClient throwing = req -> {
            throw new RuntimeException("simulated LLM failure");
        };
        MemoryConsolidator consolidator = new MemoryConsolidator(store, cfg, throwing, "test-model");

        int result = assertDoesNotThrow(consolidator::consolidate);

        assertEquals(0, result);
        assertEquals(5, store.list().size(), "原 5 条应一条不少 — 这是数据安全的关键");
        // 内容也应原样
        for (int i = 0; i < 5; i++) {
            assertTrue(store.read("mem-" + i + ".md").isPresent());
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 5: LLM 返回坏 JSON → 旧文件保持不变(原子性)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM returns malformed JSON → originals preserved")
    void bad_json_preserves_originals(@TempDir Path tempDir) {
        MemoryConfig cfg = configForDir(tempDir, 3);
        MemoryStore store = new MemoryStore(cfg);
        seedMemories(store, 5);

        // LLM 返回不含 JSON 数组的文本
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("I cannot consolidate these memories."));
        MemoryConsolidator consolidator = new MemoryConsolidator(store, cfg, mock, "test-model");

        int result = consolidator.consolidate();

        assertEquals(0, result);
        assertEquals(5, store.list().size(), "坏 JSON 不应让数据丢失");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 6: LLM 返回 [] / 全 invalid → 不动磁盘(安全闸门)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM returns [] → originals preserved (safety gate)")
    void empty_result_preserves_originals(@TempDir Path tempDir) {
        MemoryConfig cfg = configForDir(tempDir, 3);
        MemoryStore store = new MemoryStore(cfg);
        seedMemories(store, 5);

        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn("[]"));
        MemoryConsolidator consolidator = new MemoryConsolidator(store, cfg, mock, "test-model");

        int result = consolidator.consolidate();

        assertEquals(0, result);
        assertEquals(5, store.list().size(),
                "LLM 返回空数组不该让用户的所有 memory 全没 — 这是安全闸门");
    }

    @Test
    @DisplayName("LLM returns all-invalid items → originals preserved")
    void all_invalid_preserves_originals(@TempDir Path tempDir) {
        MemoryConfig cfg = configForDir(tempDir, 3);
        MemoryStore store = new MemoryStore(cfg);
        seedMemories(store, 5);

        // 所有 item 都缺 body / description, 全部应被 toMemoryFile 过滤
        String llmJson = "[" +
                "{\"name\":\"a\",\"type\":\"user\"}," +
                "{\"name\":\"b\",\"type\":\"user\",\"description\":\"d\"}" +
                "]";
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(llmJson));
        MemoryConsolidator consolidator = new MemoryConsolidator(store, cfg, mock, "test-model");

        int result = consolidator.consolidate();

        assertEquals(0, result);
        assertEquals(5, store.list().size(), "全 invalid 应触发安全闸门");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 7: 部分新名 + 部分旧名 → 同 name 覆盖, 不在结果的旧 name 删
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("partial overlap: existing names overwritten, missing names deleted")
    void partial_overlap_correctly_handled(@TempDir Path tempDir) {
        MemoryConfig cfg = configForDir(tempDir, 3);
        MemoryStore store = new MemoryStore(cfg);
        // 写 mem-0 ~ mem-4
        seedMemories(store, 5);

        // LLM 返回 3 条:
        // - mem-0: 保留, 但更新 description (覆盖)
        // - mem-2: 保留, 内容不变
        // - new-merged: 全新
        // → mem-1, mem-3, mem-4 应被删
        String llmJson = "[" +
                "{\"name\":\"mem-0\",\"type\":\"user\"," +
                "\"description\":\"updated desc 0\",\"body\":\"updated body 0\"}," +
                "{\"name\":\"mem-2\",\"type\":\"user\"," +
                "\"description\":\"desc 2\",\"body\":\"body 2\"}," +
                "{\"name\":\"new-merged\",\"type\":\"feedback\"," +
                "\"description\":\"new merged\",\"body\":\"merged content\"}" +
                "]";
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(llmJson));
        MemoryConsolidator consolidator = new MemoryConsolidator(store, cfg, mock, "test-model");

        int result = consolidator.consolidate();

        assertEquals(3, result);
        assertEquals(3, store.list().size());

        // mem-0: 内容应该被更新
        MemoryFile m0 = store.read("mem-0.md").orElseThrow();
        assertEquals("updated desc 0", m0.getDescription());
        assertTrue(m0.getBody().contains("updated body 0"));

        // mem-2: 仍存在
        assertTrue(store.read("mem-2.md").isPresent());

        // new-merged: 新加的
        assertTrue(store.read("new-merged.md").isPresent());

        // 不在结果里的应被删
        assertTrue(store.read("mem-1.md").isEmpty());
        assertTrue(store.read("mem-3.md").isEmpty());
        assertTrue(store.read("mem-4.md").isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 8: 部分 item 缺字段 → 跳过该条, 其他正常
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("partial invalid items skipped, valid ones processed")
    void partial_invalid_items_skipped(@TempDir Path tempDir) {
        MemoryConfig cfg = configForDir(tempDir, 3);
        MemoryStore store = new MemoryStore(cfg);
        seedMemories(store, 5);

        // 4 条:2 valid, 2 invalid (缺 body / description)
        String llmJson = "[" +
                "{\"name\":\"good-1\",\"type\":\"user\"," +
                "\"description\":\"d1\",\"body\":\"b1\"}," +
                "{\"name\":\"missing-body\",\"type\":\"user\",\"description\":\"d\"}," +
                "{\"name\":\"missing-desc\",\"type\":\"user\",\"body\":\"b\"}," +
                "{\"name\":\"good-2\",\"type\":\"user\"," +
                "\"description\":\"d2\",\"body\":\"b2\"}" +
                "]";
        MockAnthropicClient mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.endTurn(llmJson));
        MemoryConsolidator consolidator = new MemoryConsolidator(store, cfg, mock, "test-model");

        int result = consolidator.consolidate();

        assertEquals(2, result, "只有 2 条 valid item");
        assertTrue(store.read("good-1.md").isPresent());
        assertTrue(store.read("good-2.md").isPresent());
        assertTrue(store.read("missing-body.md").isEmpty());
        assertTrue(store.read("missing-desc.md").isEmpty());
        // 旧的 mem-0 ~ mem-4 也应都被删(没在结果里)
        for (int i = 0; i < 5; i++) {
            assertTrue(store.read("mem-" + i + ".md").isEmpty());
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 9: catalog 渲染格式
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("catalog rendering: each memory as ## heading + fields + body")
    void catalog_rendering_format(@TempDir Path tempDir) {
        MemoryConfig cfg = configForDir(tempDir, 3);
        MemoryStore store = new MemoryStore(cfg);
        store.write(MemoryFile.of("foo", MemoryFile.Type.USER,
                "foo desc", "foo body content"));
        store.write(MemoryFile.of("bar", MemoryFile.Type.FEEDBACK,
                "bar desc", "bar body content"));

        MemoryConsolidator consolidator = new MemoryConsolidator(store, cfg, null, null);

        String catalog = consolidator.renderCatalog(store.list());

        assertTrue(catalog.contains("## foo.md"));
        assertTrue(catalog.contains("## bar.md"));
        assertTrue(catalog.contains("name: foo"));
        assertTrue(catalog.contains("name: bar"));
        assertTrue(catalog.contains("type: user"));
        assertTrue(catalog.contains("type: feedback"));
        assertTrue(catalog.contains("foo body content"));
        assertTrue(catalog.contains("bar body content"));
    }
}
