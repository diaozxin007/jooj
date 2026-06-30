package com.xilidou.jooj.slashcmd.impl;

import com.xilidou.jooj.memory.MemoryConfig;
import com.xilidou.jooj.memory.MemoryFile;
import com.xilidou.jooj.memory.MemoryStore;
import com.xilidou.jooj.memory.PendingMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link MemoryCommand} 派发行为(s21 Demo 27 P3.2):
 *
 * <ul>
 *   <li>无参 / "pending" → 列待审批</li>
 *   <li>"approve N" → 把 #N promote 到 store 并从 pending pool 移除</li>
 *   <li>"reject N" → 直接从 pending pool 移除,不写 store</li>
 *   <li>"approve <非数字>" → friendly error</li>
 *   <li>"approve 999"(不存在 id) → friendly error</li>
 *   <li>未知 action → friendly error 列出可用 action</li>
 *   <li>"clear" → 一键清空 pending pool</li>
 * </ul>
 */
class MemoryCommandTest {

    @TempDir
    Path tempDir;

    PendingMemoryStore pendingStore;
    MemoryStore memoryStore;
    MemoryCommand cmd;

    @BeforeEach
    void setUp() {
        pendingStore = new PendingMemoryStore(tempDir);
        memoryStore = new MemoryStore(new MemoryConfig(tempDir, "MEMORY.md", 4096, 10));
        cmd = new MemoryCommand(pendingStore, memoryStore);
    }

    private void seedProposal(String name, String desc) {
        pendingStore.propose(MemoryFile.of(name, MemoryFile.Type.FEEDBACK, desc, "Body of " + name),
                "reviewer");
    }

    @Test
    @DisplayName("空 pool 时 /memory pending → 'No pending memory proposals.'")
    void pending_empty_returns_friendly() {
        String out = cmd.execute("", "any-session");
        assertEquals("No pending memory proposals.", out);
    }

    @Test
    @DisplayName("/memory(无参)= /memory pending(默认 action)")
    void no_args_equals_pending() {
        seedProposal("feedback-prefer-rg", "User prefers ripgrep over grep");
        String out = cmd.execute("", "any-session");
        assertTrue(out.contains("Pending memory proposals (1)"));
        assertTrue(out.contains("feedback-prefer-rg"));
        assertTrue(out.contains("/memory approve <id>"));
    }

    @Test
    @DisplayName("/memory pending 显式 action 跟无参一致")
    void explicit_pending_action() {
        seedProposal("feedback-x", "x");
        String outImplicit = cmd.execute("", "s");
        String outExplicit = cmd.execute("pending", "s");
        assertEquals(outImplicit, outExplicit);
    }

    @Test
    @DisplayName("approve <existing id> → promote 到 store + 从 pool 移除")
    void approve_promotes_to_store() {
        seedProposal("feedback-x", "lesson learned");
        // pending 池里有一条 id=1
        assertEquals(1, pendingStore.count());
        assertEquals(0, memoryStore.list().size());

        String out = cmd.execute("approve 1", "s");
        assertTrue(out.startsWith("✓ Approved #1"));
        assertTrue(out.contains("feedback-x"));

        // 验证副作用:pool 空了 + store 有了
        assertEquals(0, pendingStore.count());
        assertEquals(1, memoryStore.list().size());
        assertEquals("feedback-x", memoryStore.list().get(0).getName());
    }

    @Test
    @DisplayName("reject <existing id> → 从 pool 移除,不写 store")
    void reject_drops() {
        seedProposal("feedback-bad", "bad proposal");
        String out = cmd.execute("reject 1", "s");
        assertTrue(out.startsWith("✓ Rejected #1"));
        assertEquals(0, pendingStore.count());
        assertEquals(0, memoryStore.list().size(), "reject 不应写 store");
    }

    @Test
    @DisplayName("approve 不存在的 id → friendly error")
    void approve_unknown_id() {
        String out = cmd.execute("approve 999", "s");
        assertTrue(out.startsWith("Error:") && out.contains("999"));
    }

    @Test
    @DisplayName("approve 非数字参数 → friendly error")
    void approve_non_numeric() {
        String out = cmd.execute("approve foo", "s");
        assertTrue(out.startsWith("Error:") && out.toLowerCase().contains("numeric"));
    }

    @Test
    @DisplayName("approve 缺参数 → friendly error")
    void approve_missing_id() {
        String out = cmd.execute("approve", "s");
        assertTrue(out.startsWith("Error:") && out.toLowerCase().contains("numeric"));
    }

    @Test
    @DisplayName("未知 action → 列出可用 action")
    void unknown_action_lists_available() {
        String out = cmd.execute("frobnicate 1", "s");
        assertTrue(out.startsWith("Unknown /memory action"));
        assertTrue(out.contains("approve") && out.contains("reject") && out.contains("pending"));
    }

    @Test
    @DisplayName("clear → 一键清空 pool 不写 store")
    void clear_drops_all() {
        seedProposal("a", "x");
        seedProposal("b", "y");
        assertEquals(2, pendingStore.count());

        String out = cmd.execute("clear", "s");
        assertTrue(out.contains("Cleared 2"));
        assertEquals(0, pendingStore.count());
        assertEquals(0, memoryStore.list().size());
    }

    @Test
    @DisplayName("clear empty pool → friendly empty message")
    void clear_empty() {
        String out = cmd.execute("clear", "s");
        assertTrue(out.contains("already empty"));
    }

    @Test
    @DisplayName("name() / description() 暴露给 Registry")
    void metadata() {
        assertEquals("memory", cmd.name());
        assertNotNull(cmd.description());
        assertFalse(cmd.description().isBlank());
    }

    // ─────────────────────────────────────────────────────────────
    //  s21 Demo 27 review 修复(BUG #4):store.write 失败时回滚到 pending pool
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BUG #4:approve 时 store quota 超 → 回滚到 pending pool 保留原 id")
    void approve_quota_failure_restores_to_pending() {
        // 用一个超小 quota 的 store(totalMaxBytes=10)让 write 必抛 MemoryQuotaExceededException
        MemoryStore tinyQuotaStore = new MemoryStore(
                new MemoryConfig(tempDir, "MEMORY.md", 4096, 10, 10));
        MemoryCommand cmdWithTinyStore = new MemoryCommand(pendingStore, tinyQuotaStore);

        // 提一个 body 比 quota 大的提案
        pendingStore.propose(MemoryFile.of("feedback-big", MemoryFile.Type.FEEDBACK,
                "big lesson", "this body is way longer than 10 chars allowed by quota"),
                "reviewer");
        assertEquals(1, pendingStore.count());

        String out = cmdWithTinyStore.execute("approve 1", "s");

        // 期望:store quota 抛,但 entry 已 restore 回 pending pool
        assertTrue(out.startsWith("✗ Approved #1"));
        assertTrue(out.contains("Restored to pending pool"),
                "应该明确告知用户已 restore,鼓励重试。实际:" + out);
        assertTrue(out.contains("retry /memory approve 1"));

        // 关键不变量:pool 仍有这条 + 仍是 id=1
        assertEquals(1, pendingStore.count(), "restore 后 pool 又回到 1 条");
        var entry = pendingStore.readAll().get(0);
        assertEquals(1, entry.getId(), "原 id=1 保留,用户可 /memory approve 1 重试");
        assertEquals("feedback-big", entry.getMemory().getName());

        // store 没写入
        assertEquals(0, tinyQuotaStore.list().size());
    }
}
