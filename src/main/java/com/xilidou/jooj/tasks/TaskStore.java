package com.xilidou.jooj.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Task 文件存储层 —— 纯 I/O,跟 {@link com.xilidou.jooj.memory.MemoryStore} 同形态。
 *
 * <p>对应 Python s12 的 {@code save_task / load_task / list_tasks / _task_path}。
 *
 * <h3>磁盘格式</h3>
 *
 * <pre>
 *   tasksDir/
 *   ├── task_1729000000_3812.json    ← 一条 task 一个 JSON
 *   ├── task_1729000005_4711.json
 *   └── ...
 * </pre>
 *
 * <p>没有索引文件 —— {@link #list} 直接 glob {@code task_*.json} 并按文件名排序
 * (id 自带 unix timestamp,字典序 ≈ 时间序)。
 *
 * <h3>线程安全</h3>
 *
 * <p>不保证。教学版假设单进程单线程(REPL 单 agent loop),与上游 Python 严格一致。
 * 生产场景需要 file lock。
 *
 * <h3>路径穿越防御</h3>
 *
 * <p>跟 MemoryStore 同模式:{@link #validateId} 拒绝包含 {@code /} {@code \\}
 * {@code ..} 的 id;最终 path 用 {@link Path#startsWith} 验证落在 {@code tasksDir} 内。
 */
@Slf4j
public class TaskStore {

    /** task 文件的命名前缀 + 后缀,跟 Python {@code TASKS_DIR.glob("task_*.json")} 严格一致。 */
    private static final String FILE_PREFIX = "task_";
    private static final String FILE_SUFFIX = ".json";

    private final TaskConfig config;
    private final ObjectMapper json;

    public TaskStore(TaskConfig config, ObjectMapper json) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (json == null) throw new IllegalArgumentException("json must not be null");
        this.config = config;
        this.json = json;
    }

    // ─────────────────────────────────────────────────────────────
    //  写入
    // ─────────────────────────────────────────────────────────────

    /**
     * 写一条 task —— 序列化为 JSON 落盘。已存在的 id 直接覆盖(对应 Python {@code save_task})。
     *
     * @return 落盘后的完整路径
     */
    public Path write(TaskRecord task) {
        if (task == null) throw new IllegalArgumentException("task must not be null");
        if (task.getId() == null || task.getId().isBlank()) {
            throw new IllegalArgumentException("task.id must not be blank");
        }
        validateId(task.getId());

        try {
            Files.createDirectories(config.tasksDir());
            Path file = pathFor(task.getId());

            // 安全断言:确认文件落在 tasksDir 内(防御)
            if (!file.toAbsolutePath().normalize().startsWith(
                    config.tasksDir().toAbsolutePath().normalize())) {
                throw new IllegalArgumentException(
                        "Resolved path escapes tasksDir: " + file);
            }

            String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(task);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            log.info("[Tasks] wrote {} ({})", task.getId(), task.getStatus());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write task: " + task.getId(), e);
        }
    }

    /** 删除一条 task。文件不存在时静默成功(返回 false)。 */
    public boolean delete(String id) {
        validateId(id);
        try {
            return Files.deleteIfExists(pathFor(id));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete task: " + id, e);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  读取
    // ─────────────────────────────────────────────────────────────

    /** 读一条 task,文件不存在或反序列化失败返回 empty(不抛)。 */
    public Optional<TaskRecord> read(String id) {
        validateId(id);
        Path file = pathFor(id);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            TaskRecord t = json.readValue(text, TaskRecord.class);
            return Optional.ofNullable(t);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            log.warn("[Tasks] failed to parse {}: {}", id, e.toString());
            return Optional.empty();
        }
    }

    /** 文件存在性检查 —— {@link TaskService#canStart} 在 dep 缺失时用。 */
    public boolean exists(String id) {
        validateId(id);
        return Files.exists(pathFor(id));
    }

    /**
     * 列出所有 task,按文件名排序。id 自带 unix ts,所以这等价于按创建时间升序
     * (跟 Python {@code sorted(TASKS_DIR.glob("task_*.json"))} 一致)。
     *
     * <p>解析失败的文件会被跳过 + warn,不让单个坏文件让整个列表崩。
     */
    public List<TaskRecord> list() {
        if (!Files.isDirectory(config.tasksDir())) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(config.tasksDir())) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);
            }).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list tasksDir", e);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        List<TaskRecord> out = new ArrayList<>();
        for (Path p : files) {
            try {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                TaskRecord t = json.readValue(text, TaskRecord.class);
                if (t != null) out.add(t);
            } catch (Exception e) {
                log.warn("[Tasks] failed to parse {}: {}", p.getFileName(), e.toString());
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    //  内部工具
    // ─────────────────────────────────────────────────────────────

    /** id → 文件路径。 */
    Path pathFor(String id) {
        return config.tasksDir().resolve(id + FILE_SUFFIX);
    }

    /** 拒绝可能造成路径穿越的 id。 */
    private void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("task id must not be blank");
        }
        if (id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new IllegalArgumentException(
                    "task id must not contain path separators or '..': " + id);
        }
    }
}
