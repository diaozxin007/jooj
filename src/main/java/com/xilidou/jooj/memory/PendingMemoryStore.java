package com.xilidou.jooj.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xilidou.jooj.config.JacksonConfig;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pending memory store —— Hermes Tier 3 P3.2 staged-write 入口(s21 Demo 27)。
 *
 * <h3>跟 {@link MemoryStore} 的关系</h3>
 *
 * <p>{@link BackgroundReviewer} 提案的 lesson 经常**质量参差** —— 有时 LLM 把单次 fact 误判
 * 为模式,有时把无关闲聊抽成 "feedback"。直接走 {@link MemoryStore#write} 落盘 = 让噪音
 * 永久污染 always-on memory。Hermes write_approval 设计:**Reviewer 写到 staged pool,
 * 用户 `/memory approve <id>` 才 promote 到正式 store**。
 *
 * <h3>存储模型</h3>
 *
 * <p>单 JSON 文件 {@code .memory/.pending.json},内容 = `List<PendingMemory>`:
 * <ul>
 *   <li>id —— monotonic 自增,用户 approve 时按 id 引用</li>
 *   <li>proposedAt —— epoch ms,展示给用户判断时效性</li>
 *   <li>source —— 'reviewer' / 'extractor' / etc(扩展用)</li>
 *   <li>memory —— 完整 {@link MemoryFile}(name / type / description / body)</li>
 * </ul>
 *
 * <p>**为什么单文件 JSON 不是 SQLite**:pending pool 期望 < 50 条(用户每天 approve 几次),
 * SQLite 过载;JSON 可以手编辑,跟 jooj 整体"JSON 是 source-of-truth"哲学一致(
 * 跟 SessionStore / TaskStore / MemoryStore 同模式)。
 *
 * <h3>id 生成策略</h3>
 *
 * <p>使用 {@link AtomicLong} 持当前 max id,启动时 `readAll() + max(id)` 初始化。
 * approve / reject 移除条目不重用 id,保证 id 全局单调 —— 用户敲 `/memory approve 7`
 * 永远指那一条,不会因为别人 approve 了 5 把 7 顶上来。
 */
@Slf4j
public class PendingMemoryStore {

    /** 文件名:藏 dotfile 防 MemoryStore.list 把它当 memory 加载 */
    public static final String PENDING_FILE = ".pending.json";

    /** Pending 池最大容量;超过就丢最老的(用户没及时 approve 的不该堵满磁盘) */
    public static final int MAX_PENDING = 100;

    private final Path pendingPath;
    private final ObjectMapper json;
    private final AtomicLong nextId = new AtomicLong(0);

    public PendingMemoryStore(Path memoryDir) {
        if (memoryDir == null) throw new IllegalArgumentException("memoryDir must not be null");
        this.pendingPath = memoryDir.resolve(PENDING_FILE);
        this.json = JacksonConfig.newMapper();
        this.json.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.json.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 初始化 nextId:扫已有最大 id + 1,保证重启后继续单调
        long maxExisting = readAll().stream().mapToLong(PendingMemory::getId).max().orElse(0);
        nextId.set(maxExisting + 1);
    }

    /** 读全部 pending(失败返空 list,不抛 —— pending 是辅助路径不该挡主流程) */
    public synchronized List<PendingMemory> readAll() {
        if (!Files.exists(pendingPath)) return new ArrayList<>();
        try {
            byte[] bytes = Files.readAllBytes(pendingPath);
            if (bytes.length == 0) return new ArrayList<>();
            return json.readValue(bytes, new TypeReference<List<PendingMemory>>() {});
        } catch (IOException e) {
            log.warn("[Memory:Pending] readAll failed: {}", e.toString());
            return new ArrayList<>();
        }
    }

    /**
     * 提交一条新提案,返回带 id / proposedAt 的对象。
     *
     * <p>**事务性**(s21 Demo 27 review 修复):写盘成功才 commit nextId 的 increment,
     * 失败抛出 + nextId 不动 + 已读出的 existing list 也未受损 —— 调用方看见的状态
     * 跟"propose 没发生"完全一致。
     *
     * <p>失败抛 {@link UncheckedIOException},BackgroundReviewer 包了 try/catch warn 不抛,
     * 主路径不受影响。
     */
    public synchronized PendingMemory propose(MemoryFile mem, String source) {
        if (mem == null) throw new IllegalArgumentException("mem must not be null");
        List<PendingMemory> existing = readAll();

        // 容量保护:超 MAX_PENDING 丢最老的(按 proposedAt 升序,删第一个)
        // 注意:这里只 mutate 本地 list;writeAll 失败时磁盘上老条目仍在(原子)
        while (existing.size() >= MAX_PENDING) {
            PendingMemory dropped = existing.remove(0);
            log.warn("[Memory:Pending] pool full ({} entries), dropping oldest id={} ({})",
                    MAX_PENDING, dropped.getId(), dropped.getMemory().getName());
        }

        // 用 nextId.get() 取候选 id 但不 increment —— 写盘成功后才 commit,失败时不漏号
        long candidateId = nextId.get();
        PendingMemory entry = new PendingMemory(
                candidateId,
                Instant.now().toEpochMilli(),
                source == null || source.isBlank() ? "reviewer" : source,
                mem
        );
        existing.add(entry);
        writeAll(existing);  // 失败抛 UncheckedIOException → 下面的 increment 不会执行
        // ↑ 写盘成功才到这里,commit increment;并发安全因为整个方法 synchronized
        nextId.set(candidateId + 1);

        log.info("[Memory:Pending] proposed id={} name={} source={}",
                entry.getId(), mem.getName(), entry.getSource());
        return entry;
    }

    /** 按 id 查找一条提案。 */
    public synchronized Optional<PendingMemory> get(long id) {
        return readAll().stream().filter(p -> p.getId() == id).findFirst();
    }

    /**
     * Approve 一条提案 —— 从 pending pool 移除,返回完整 {@link PendingMemory}
     * (含原 id + proposedAt + source)给调用方 promote 到 store。
     *
     * <p>**接口 design 提示**:返完整 PendingMemory 而不是裸 MemoryFile,是为了让
     * caller 在 store.write 失败时能调 {@link #restore} 用原 id 回滚 ——
     * 从而 user 先前看到的 `/memory pending` 列表里的 #7 在失败后仍是 #7,可重试。
     *
     * @return Optional 包装(id 不存在时 empty)
     */
    public synchronized Optional<PendingMemory> approve(long id) {
        List<PendingMemory> all = readAll();
        Optional<PendingMemory> match = all.stream().filter(p -> p.getId() == id).findFirst();
        if (match.isEmpty()) {
            return Optional.empty();
        }
        all.remove(match.get());
        writeAll(all);
        log.info("[Memory:Pending] approved id={} name={}",
                id, match.get().getMemory().getName());
        return match;
    }

    /** Reject 一条提案 —— 直接从 pool 移除,**不**写到 store。 */
    public synchronized boolean reject(long id) {
        List<PendingMemory> all = readAll();
        boolean removed = all.removeIf(p -> p.getId() == id);
        if (removed) {
            writeAll(all);
            log.info("[Memory:Pending] rejected id={}", id);
        }
        return removed;
    }

    /**
     * 把一条已 approve 的 entry 放回 pool —— 用于 store.write 失败时的回滚补救。
     *
     * <p>跟 {@link #propose} 不同:**保留原 id + 原 proposedAt**(不 increment nextId)。
     * 这样用户先前看到的 `/memory pending` 列表里的 #7,失败后再 `/memory pending` 仍是同一条
     * #7,可以重试 approve。
     *
     * <p>幂等防护:如果 pool 里已经有同 id 的条目(不该发生但兜底),不会重复添加。
     *
     * <p>失败抛 {@link UncheckedIOException}(写盘失败 + 内存里 entry 引用 caller 仍持有)。
     */
    public synchronized void restore(PendingMemory entry) {
        if (entry == null) throw new IllegalArgumentException("entry must not be null");
        List<PendingMemory> all = readAll();
        // 幂等:同 id 已存在不重复添加
        if (all.stream().anyMatch(p -> p.getId() == entry.getId())) {
            log.warn("[Memory:Pending] restore skipped, id={} already in pool", entry.getId());
            return;
        }
        all.add(entry);
        writeAll(all);
        log.info("[Memory:Pending] restored id={} name={} (rollback after store.write failure)",
                entry.getId(), entry.getMemory().getName());
    }

    /** 当前 pending 数量(供 catalog / status 显示)。 */
    public synchronized int count() {
        return readAll().size();
    }

    /** 全清空 —— 给 /memory clear-pending 用,谨慎调。 */
    public synchronized int clear() {
        int n = count();
        writeAll(new ArrayList<>());
        log.info("[Memory:Pending] cleared {} entries", n);
        return n;
    }

    // ── 内部 IO ────────────────────────────────────────────

    private synchronized void writeAll(List<PendingMemory> entries) {
        try {
            Files.createDirectories(pendingPath.getParent());
            byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(entries);
            Files.write(pendingPath, bytes);
        } catch (IOException e) {
            log.warn("[Memory:Pending] writeAll failed: {}", e.toString());
            throw new UncheckedIOException(e);
        }
    }

    // ── DTO ────────────────────────────────────────────────

    /**
     * 一条 staged 提案。Lombok @Data 给 Jackson 走 setter 反序列化。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingMemory {
        /** 单调自增,用户 approve 时按这个引用 */
        private long id;
        /** epoch milliseconds */
        private long proposedAt;
        /** 'reviewer' / 'extractor' / 'manual' */
        private String source;
        /** 完整 MemoryFile 提案 */
        private MemoryFile memory;
    }
}
