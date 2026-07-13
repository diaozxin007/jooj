package com.xilidou.jooj.transcript;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Transcript 的 IO 层 —— 按行 append 到 {@code <sid>.jsonl},独立可测。
 *
 * <h3>文件布局</h3>
 * <pre>
 * ~/.jooj/transcripts/
 *   ├── s1.jsonl                             活跃 session
 *   ├── s2.jsonl                             活跃 session
 *   └── .deleted/
 *       ├── s3-1720771200000.jsonl           已删 session(D6 软归档,epoch ms 后缀)
 *       └── s4-1720774800000.jsonl
 * </pre>
 *
 * <h3>并发假设(D12)</h3>
 *
 * <p>单 JVM 部署,靠 {@code pidfile guard} 阻断第二个实例。文件 append 无锁,依赖:
 * <ol>
 *   <li>Linux/APFS 上 &lt;PIPE_BUF (4KB) 的 append 系统调用原子</li>
 *   <li>TranscriptLine 序列化通常 &lt; 1KB,单行 append 天然原子</li>
 * </ol>
 *
 * <p>不支持多进程并发写同一文件 —— 若未来 SaaS 化必须换存储层。
 *
 * <h3>不参与业务逻辑</h3>
 *
 * <p>只关心"给一个 sid 能 append 一行 / 能读出所有行 / 能软归档"。所有事件处理、去重、
 * 错误恢复放在 {@link TranscriptService}。
 */
@Slf4j
public class TranscriptStore {

    private static final TypeReference<TranscriptLine> LINE_TYPE = new TypeReference<>() {
    };

    /** 已删归档子目录名,前缀 . 隐藏。 */
    static final String DELETED_SUBDIR = ".deleted";

    private final Path transcriptsDir;
    private final Path deletedDir;
    private final ObjectMapper json;

    public TranscriptStore(Path transcriptsDir, ObjectMapper json) {
        if (transcriptsDir == null) throw new IllegalArgumentException("transcriptsDir must not be null");
        if (json == null) throw new IllegalArgumentException("json must not be null");
        this.transcriptsDir = transcriptsDir;
        this.deletedDir = transcriptsDir.resolve(DELETED_SUBDIR);
        this.json = json;
    }

    public Path transcriptsDir() {
        return transcriptsDir;
    }

    public Path deletedDir() {
        return deletedDir;
    }

    // ── Append ─────────────────────────────────────────────────

    /**
     * 追加一行到 {@code <sid>.jsonl}。目录不存在会自动创建。
     *
     * @throws IOException 磁盘满 / 权限错误等;caller(TranscriptService)负责 catch 并 warn log
     */
    public void append(String sessionId, TranscriptLine line) throws IOException {
        validateSessionId(sessionId);
        if (line == null) throw new IllegalArgumentException("line must not be null");
        Files.createDirectories(transcriptsDir);
        Path path = filePath(sessionId);
        String jsonLine = json.writeValueAsString(line) + "\n";
        Files.writeString(path, jsonLine,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ── Read ───────────────────────────────────────────────────

    /**
     * 读出一个 session 的所有 transcript 行,按文件顺序返回。
     *
     * <p>文件不存在时返空 list(空 session / 从未 append 过) —— 不当错误。
     * 单行 JSON 解析失败时跳过该行并 warn log,不中断整份读取(防止一行损坏
     * 让整个 session 加载失败)。
     */
    public List<TranscriptLine> readAll(String sessionId) throws IOException {
        validateSessionId(sessionId);
        Path path = filePath(sessionId);
        if (!Files.exists(path)) return List.of();
        List<TranscriptLine> lines = new ArrayList<>();
        try (Stream<String> stream = Files.lines(path)) {
            stream.forEach(raw -> {
                if (raw.isBlank()) return;
                try {
                    lines.add(json.readValue(raw, LINE_TYPE));
                } catch (JsonProcessingException e) {
                    // 一行损坏不阻断整份读取(防"一行 malformed 让整个 session 加载失败")。
                    // 只 catch JSON 解析异常;其他真实 IO 错误(比如 stream 中途读断)
                    // 会作为 UncheckedIOException 从 forEach 里冒泡,由 try-with-resources
                    // 关闭 stream 后传给 caller。
                    log.warn("[Transcript] malformed line in {}: {}", sessionId, e.toString());
                }
            });
        }
        return lines;
    }

    // ── Soft Delete (D6) ───────────────────────────────────────

    /**
     * 软归档:把 {@code <sid>.jsonl} 移到 {@code .deleted/<sid>-<epochMs>.jsonl}。
     *
     * <p>文件不存在时 no-op(idempotent) —— 反复调用不抛。
     *
     * @param sessionId 目标 session
     * @param at        软归档时间戳,用作文件名后缀,防止同 sid 反复创建+删除时冲突
     */
    public void softDelete(String sessionId, Instant at) throws IOException {
        validateSessionId(sessionId);
        if (at == null) throw new IllegalArgumentException("at must not be null");
        Path src = filePath(sessionId);
        if (!Files.exists(src)) return;
        Files.createDirectories(deletedDir);
        Path dst = deletedDir.resolve(sessionId + "-" + at.toEpochMilli() + ".jsonl");
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // 极少数跨 filesystem 场景兜底 —— 非原子但功能等价
            Files.move(src, dst);
        }
    }

    // ── 内部工具 ────────────────────────────────────────────────

    private Path filePath(String sessionId) {
        return transcriptsDir.resolve(sessionId + ".jsonl");
    }

    /** 跟 SessionStore 同样的路径注入防御。 */
    private static void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (!sessionId.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("invalid sessionId: " + sessionId);
        }
    }
}
