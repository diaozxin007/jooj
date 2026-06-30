package com.xilidou.jooj.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link PendingMemoryStore} 的核心行为(s21 Demo 27 / Hermes Tier 3 P3.2):
 *
 * <ul>
 *   <li>propose 自增 id + 持久化</li>
 *   <li>readAll 读出顺序跟 propose 顺序一致</li>
 *   <li>approve 移除条目并返原 MemoryFile</li>
 *   <li>reject 移除条目不返</li>
 *   <li>id 不存在时 approve / reject 安全(Optional.empty / false)</li>
 *   <li>重启(new instance 同 dir)id 继续单调,不重用</li>
 *   <li>容量保护:超 MAX_PENDING 丢最老</li>
 *   <li>clear 一键清空</li>
 *   <li>JSON 文件不存在 readAll 返空(不抛)</li>
 * </ul>
 */
class PendingMemoryStoreTest {

    private static MemoryFile sample(String name, MemoryFile.Type type, String desc) {
        return MemoryFile.of(name, type, desc, "Body content for " + name);
    }

    @Test
    @DisplayName("空目录 readAll 返空 list 不抛")
    void empty_dir_returns_empty(@TempDir Path tempDir) {
        PendingMemoryStore store = new PendingMemoryStore(tempDir);
        assertTrue(store.readAll().isEmpty());
        assertEquals(0, store.count());
    }

    @Test
    @DisplayName("propose 一条 → readAll 返 1 条 + id 从 1 开始单调")
    void propose_assigns_monotonic_id(@TempDir Path tempDir) {
        PendingMemoryStore store = new PendingMemoryStore(tempDir);
        var p1 = store.propose(sample("a", MemoryFile.Type.FEEDBACK, "first"), "reviewer");
        var p2 = store.propose(sample("b", MemoryFile.Type.FEEDBACK, "second"), "reviewer");

        assertEquals(1, p1.getId());
        assertEquals(2, p2.getId());
        assertTrue(p1.getProposedAt() > 0);
        assertEquals("reviewer", p1.getSource());
        assertEquals(2, store.count());
    }

    @Test
    @DisplayName("approve 已存在 id → 移除 + 返完整 PendingMemory(含原 id / proposedAt / source)")
    void approve_removes_and_returns(@TempDir Path tempDir) {
        PendingMemoryStore store = new PendingMemoryStore(tempDir);
        store.propose(sample("a", MemoryFile.Type.FEEDBACK, "first"), "reviewer");
        store.propose(sample("b", MemoryFile.Type.FEEDBACK, "second"), "reviewer");

        Optional<PendingMemoryStore.PendingMemory> approved = store.approve(1);
        assertTrue(approved.isPresent());
        assertEquals(1, approved.get().getId(), "返 PendingMemory 含原 id 给 caller 失败时 restore");
        assertEquals("a", approved.get().getMemory().getName());
        assertEquals("reviewer", approved.get().getSource());
        assertEquals(1, store.count(), "approve 后只剩 #2");
    }

    @Test
    @DisplayName("reject 已存在 id → true + 移除")
    void reject_removes(@TempDir Path tempDir) {
        PendingMemoryStore store = new PendingMemoryStore(tempDir);
        store.propose(sample("a", MemoryFile.Type.FEEDBACK, "first"), "reviewer");
        assertTrue(store.reject(1));
        assertEquals(0, store.count());
    }

    @Test
    @DisplayName("approve / reject 不存在 id → 安全(empty / false)")
    void unknown_id_is_safe(@TempDir Path tempDir) {
        PendingMemoryStore store = new PendingMemoryStore(tempDir);
        store.propose(sample("a", MemoryFile.Type.FEEDBACK, "x"), "reviewer");

        assertTrue(store.approve(999).isEmpty());
        assertFalse(store.reject(999));
        assertEquals(1, store.count(), "未知 id 操作不影响 pool");
    }

    @Test
    @DisplayName("重启(new instance 同 dir):id 继续单调")
    void id_monotonic_across_instances(@TempDir Path tempDir) {
        PendingMemoryStore s1 = new PendingMemoryStore(tempDir);
        s1.propose(sample("a", MemoryFile.Type.FEEDBACK, "x"), "reviewer");
        s1.propose(sample("b", MemoryFile.Type.FEEDBACK, "y"), "reviewer");
        // 模拟重启 — new instance 同目录
        PendingMemoryStore s2 = new PendingMemoryStore(tempDir);
        var p3 = s2.propose(sample("c", MemoryFile.Type.FEEDBACK, "z"), "reviewer");
        assertEquals(3, p3.getId(), "重启后 id 应继续单调,不重用 1/2");
    }

    @Test
    @DisplayName("approve 不重用 id:剩下的 id 保持原值")
    void approve_does_not_reuse_id(@TempDir Path tempDir) {
        PendingMemoryStore store = new PendingMemoryStore(tempDir);
        store.propose(sample("a", MemoryFile.Type.FEEDBACK, "x"), "reviewer");
        var p2 = store.propose(sample("b", MemoryFile.Type.FEEDBACK, "y"), "reviewer");
        assertEquals(2, p2.getId());

        store.approve(1);

        // p2 仍然是 id=2,不会被顶到 1
        var remaining = store.readAll();
        assertEquals(1, remaining.size());
        assertEquals(2, remaining.get(0).getId());

        // 下一次 propose 应是 id=3,不是 id=2
        var p3 = store.propose(sample("c", MemoryFile.Type.FEEDBACK, "z"), "reviewer");
        assertEquals(3, p3.getId());
    }

    @Test
    @DisplayName("clear 一次性清空 pool")
    void clear_empties_pool(@TempDir Path tempDir) {
        PendingMemoryStore store = new PendingMemoryStore(tempDir);
        store.propose(sample("a", MemoryFile.Type.FEEDBACK, "x"), "reviewer");
        store.propose(sample("b", MemoryFile.Type.FEEDBACK, "y"), "reviewer");
        assertEquals(2, store.clear());
        assertEquals(0, store.count());
    }

    @Test
    @DisplayName("文件夹下出现意外的 .pending.json 损坏 → readAll 返空不抛")
    void corrupted_json_returns_empty(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve(PendingMemoryStore.PENDING_FILE),
                "this is not valid json {{{");
        PendingMemoryStore store = new PendingMemoryStore(tempDir);
        assertTrue(store.readAll().isEmpty(),
                "损坏 JSON 不该挡 staged pool 路径,只是返空 + log warn");
    }

    @Test
    @DisplayName("source 缺失 → default 'reviewer'")
    void source_default_is_reviewer(@TempDir Path tempDir) {
        PendingMemoryStore store = new PendingMemoryStore(tempDir);
        var p = store.propose(sample("a", MemoryFile.Type.FEEDBACK, "x"), null);
        assertEquals("reviewer", p.getSource());

        var p2 = store.propose(sample("b", MemoryFile.Type.FEEDBACK, "y"), "");
        assertEquals("reviewer", p2.getSource());

        var p3 = store.propose(sample("c", MemoryFile.Type.FEEDBACK, "z"), "extractor");
        assertEquals("extractor", p3.getSource(), "显式传 source 不被 default 覆盖");
    }

    // ─────────────────────────────────────────────────────────────
    //  s21 Demo 27 review 修复(BUG #1 + #4)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BUG #1 修复:propose 写盘失败时 nextId 不 increment(下次 propose 不漏号)")
    void propose_write_fail_does_not_burn_id(@TempDir Path tempDir) throws Exception {
        // 触发 writeAll 失败:把 .pending.json 设成只读目录(memoryDir 整个 readonly 太狠,
        // 这里改成 .pending.json 已是目录,Files.write 必抛 IOException)
        PendingMemoryStore store = new PendingMemoryStore(tempDir);
        // 先正常 propose 一条,nextId 已经 → 2
        var p1 = store.propose(sample("a", MemoryFile.Type.FEEDBACK, "x"), "reviewer");
        assertEquals(1, p1.getId());

        // 删原 .pending.json,创建同名目录 → Files.write 必抛
        Path pending = tempDir.resolve(PendingMemoryStore.PENDING_FILE);
        java.nio.file.Files.delete(pending);
        java.nio.file.Files.createDirectory(pending);

        // propose 失败必须抛 UncheckedIOException
        assertThrows(java.io.UncheckedIOException.class,
                () -> store.propose(sample("b", MemoryFile.Type.FEEDBACK, "y"), "reviewer"));

        // 拆掉障碍恢复
        java.nio.file.Files.delete(pending);

        // 关键:nextId 没 increment,下次 propose 应仍是 id=2(不是 id=3)
        var p3 = store.propose(sample("c", MemoryFile.Type.FEEDBACK, "z"), "reviewer");
        assertEquals(2, p3.getId(), "写盘失败不应 burn id —— 否则一段时间后 id 跳号严重");
    }

    @Test
    @DisplayName("BUG #4 修复:approve 后调 restore 用原 id 把 entry 放回(给 store.write 失败回滚用)")
    void approve_then_restore_preserves_id(@TempDir Path tempDir) {
        PendingMemoryStore store = new PendingMemoryStore(tempDir);
        store.propose(sample("a", MemoryFile.Type.FEEDBACK, "x"), "reviewer");
        var approved = store.approve(1);
        assertTrue(approved.isPresent());
        assertEquals(0, store.count());

        // 模拟 store.write 失败 → caller 走 restore 回滚
        store.restore(approved.get());

        assertEquals(1, store.count(), "restore 后 pool 又有 1 条");
        var entry = store.readAll().get(0);
        assertEquals(1, entry.getId(), "原 id=1 保留(用户可重试 /memory approve 1)");
        assertEquals("a", entry.getMemory().getName());
    }

    @Test
    @DisplayName("restore 幂等:重复 restore 同 id 不重复添加")
    void restore_idempotent_for_same_id(@TempDir Path tempDir) {
        PendingMemoryStore store = new PendingMemoryStore(tempDir);
        store.propose(sample("a", MemoryFile.Type.FEEDBACK, "x"), "reviewer");
        var approved = store.approve(1).orElseThrow();
        store.restore(approved);
        store.restore(approved);  // 第二次应该 no-op
        assertEquals(1, store.count(), "重复 restore 不应造成 pool 里出现两条 id=1");
    }

    @Test
    @DisplayName("restore 不动 nextId:之后 propose 仍按 max+1 走")
    void restore_does_not_advance_next_id(@TempDir Path tempDir) {
        PendingMemoryStore store = new PendingMemoryStore(tempDir);
        store.propose(sample("a", MemoryFile.Type.FEEDBACK, "x"), "reviewer");
        store.propose(sample("b", MemoryFile.Type.FEEDBACK, "y"), "reviewer");
        // approve 1 + restore — pool 又回到 [#1, #2]
        var approved = store.approve(1).orElseThrow();
        store.restore(approved);
        assertEquals(2, store.count());

        // 下条 propose 应该 id=3(不 reuse 已 restore 的 1)
        var p3 = store.propose(sample("c", MemoryFile.Type.FEEDBACK, "z"), "reviewer");
        assertEquals(3, p3.getId(), "restore 不动 nextId,新 propose 仍 max+1");
    }
}
