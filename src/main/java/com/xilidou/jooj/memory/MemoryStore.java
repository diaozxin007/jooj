package com.xilidou.jooj.memory;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 文件系统支撑的 Memory 存储层。
 *
 * <p>对应 Python s09 的:
 * <ul>
 *   <li>{@code write_memory_file(name, type, description, body)} → {@link #write}</li>
 *   <li>{@code _rebuild_index()} → {@link #rebuildIndex}</li>
 *   <li>{@code read_memory_index()} → {@link #readIndex}</li>
 *   <li>{@code read_memory_file(filename)} → {@link #read}</li>
 *   <li>{@code list_memory_files()} → {@link #list}</li>
 * </ul>
 *
 * <p>这个类是**纯 I/O**:不调 LLM,不做选择,不做摘要。是 Memory 系统的基石,
 * 上层组件(Selector / Extractor / Consolidator)依赖它。
 *
 * <p>线程安全:不保证。教学版假设单进程单线程,生产用需要 file lock(对应 CC 的
 * {@code .consolidate-lock})。
 *
 * <p>路径穿越防御:
 * <ul>
 *   <li>{@link #write} 用 {@link MemoryFile#slugFromName(String)} 清洗 name</li>
 *   <li>{@link #read} 检查 filename 不能含 {@code /} {@code \\} {@code ..}</li>
 *   <li>所有最终路径用 {@link Path#startsWith} 验证落在 {@code memoryDir} 内</li>
 * </ul>
 */
@Slf4j
public class MemoryStore {

    /** 匹配 frontmatter 块:开头 ---,中间任意行,结束 ---。多行 + 非贪婪。*/
    private static final Pattern FRONTMATTER =
            Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$", Pattern.DOTALL);

    /** 匹配 frontmatter 内一行 {@code key: value}。允许 value 含冒号。*/
    private static final Pattern FRONTMATTER_LINE =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_-]*)\\s*:\\s*(.*?)\\s*$");

    private final MemoryConfig config;

    public MemoryStore(MemoryConfig config) {
        this.config = config;
    }

    // ─────────────────────────────────────────────────────────────
    //  写入
    // ─────────────────────────────────────────────────────────────

    /**
     * 写一个 memory 文件,然后重建索引。
     *
     * <p>同名(slug 相同)覆盖旧文件,这是教学版语义——LLM extract 时如果
     * 决定更新已有 memory,会用同样的 name 让我们覆盖。
     *
     * <p>body 超过 {@link MemoryConfig#maxBodyBytes()} 会截断 + 加 "..." 提示。
     *
     * <p><b>容量保护(s21 Demo 21)</b>:写入后总字符数会超过
     * {@link MemoryConfig#totalMaxBytes()} 时抛 {@link MemoryQuotaExceededException}。
     * 同名覆盖时把"旧 entry 的 body 字符数"从 current 减掉,只算 net 增量;否则一条
     * 老 entry 改一字也会被拒。
     *
     * @param mem 要写入的 memory(filename 字段被忽略,由 slug(name) 计算)
     * @return 落盘后的完整路径
     * @throws MemoryQuotaExceededException 写入会让总量超过 totalMaxBytes 时
     */
    public Path write(MemoryFile mem) {
        if (mem == null) throw new IllegalArgumentException("mem must not be null");
        if (mem.getName() == null || mem.getName().isBlank()) {
            throw new IllegalArgumentException("mem.name must not be blank");
        }
        if (mem.getType() == null) {
            throw new IllegalArgumentException("mem.type must not be null");
        }

        try {
            Files.createDirectories(config.memoryDir());

            String slug = MemoryFile.slugFromName(mem.getName());
            String filename = slug + ".md";
            Path file = config.memoryDir().resolve(filename);
            // 安全断言:确认文件落在 memoryDir 内
            if (!file.toAbsolutePath().normalize().startsWith(config.memoryDir().toAbsolutePath().normalize())) {
                throw new IllegalArgumentException(
                        "Resolved path escapes memoryDir: " + file);
            }

            String body = truncateBody(mem.getBody());

            // s21 Demo 21:容量检查 —— 算 net 增量(覆盖时减掉旧 entry body)
            // 计量口径:统一按 stripTrailing() 长度,跟 read 回来后的 body 一致
            // (renderFrontmatter 总会给 body 末尾加 '\n',如果不 strip,read 出的 body
            //  比写入时多 1,导致计量不一致)
            int currentTotal = totalBodyChars();
            int oldEntryBytes = readBodyChars(filename);  // 不存在返回 0
            int incoming = body.stripTrailing().length();
            int netAfter = currentTotal - oldEntryBytes + incoming;
            if (netAfter > config.totalMaxBytes()) {
                throw new MemoryQuotaExceededException(
                        currentTotal - oldEntryBytes, incoming, config.totalMaxBytes());
            }

            String content = renderFrontmatter(mem, body);
            Files.writeString(file, content, StandardCharsets.UTF_8);

            // 回填 filename 给调用方
            mem.setFilename(filename);

            // 重建索引
            rebuildIndex();

            log.info("[Memory] wrote {} ({}, {}/{} chars after)",
                    filename, mem.getType().slug(), netAfter, config.totalMaxBytes());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write memory: " + mem.getName(), e);
        }
    }

    /** 删除一条 memory(按 filename),然后重建索引。文件不存在时静默成功。*/
    public boolean delete(String filename) {
        validateFilename(filename);
        Path file = config.memoryDir().resolve(filename);
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                rebuildIndex();
                log.info("[Memory] deleted {}", filename);
            }
            return deleted;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete memory: " + filename, e);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  读取
    // ─────────────────────────────────────────────────────────────

    /**
     * 读一条 memory 文件。文件不存在或格式错误返回 empty。
     *
     * @param filename 要读的文件名,如 {@code "user-preference-tabs.md"}
     */
    public Optional<MemoryFile> read(String filename) {
        validateFilename(filename);
        Path file = config.memoryDir().resolve(filename);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(parse(text, filename));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read memory: " + filename, e);
        }
    }

    /**
     * 列出所有 memory 文件(不含索引文件本身),按 mtime 倒序排
     * (跟 CC 一致——最近修改的在前,Selector 截断时优先看它们)。
     *
     * <p>解析失败的文件会被跳过 + warn,不让单个坏文件让整个系统崩。
     */
    public List<MemoryFile> list() {
        if (!Files.isDirectory(config.memoryDir())) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(config.memoryDir())) {
            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals(config.indexFilename()))
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list memory dir", e);
        }
        // mtime 倒序
        files.sort(Comparator.comparing((Path p) -> {
            try {
                return Files.getLastModifiedTime(p);
            } catch (IOException e) {
                return null;
            }
        }, Comparator.nullsLast(Comparator.reverseOrder())));

        List<MemoryFile> out = new ArrayList<>();
        for (Path p : files) {
            try {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                out.add(parse(text, p.getFileName().toString()));
            } catch (Exception e) {
                log.warn("[Memory] failed to parse {}: {}", p.getFileName(), e.toString());
            }
        }
        return out;
    }

    /** 读索引文件。不存在返回空字符串。*/
    public String readIndex() {
        Path idx = config.indexPath();
        try {
            return Files.readString(idx, StandardCharsets.UTF_8).strip();
        } catch (NoSuchFileException e) {
            return "";
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read index", e);
        }
    }

    /**
     * 累加所有 memory 文件的 body 字符数(s21 Demo 21,容量配额计量)。
     *
     * <p>不算 frontmatter / 索引文件 —— 它们对 LLM 不可见配额,只算"真信息密度"。
     * 解析失败的文件按 0 计(跟 {@link #list} 行为一致)。
     *
     * <p>计量口径:对 body 调 {@code stripTrailing()},跟 {@link #write} 入口一致。
     */
    public int totalBodyChars() {
        int total = 0;
        for (MemoryFile m : list()) {
            String body = m.getBody();
            if (body != null) total += body.stripTrailing().length();
        }
        return total;
    }

    /**
     * 读取一个文件的 body 字符数(不存在返回 0)。s21 Demo 21 算"覆盖时旧 entry 释放的字符"用。
     *
     * <p>计量口径:对 body 调 {@code stripTrailing()},跟 {@link #write} 入口一致。
     */
    int readBodyChars(String filename) {
        return read(filename)
                .map(MemoryFile::getBody)
                .map(s -> s.stripTrailing().length())
                .orElse(0);
    }

    // ─────────────────────────────────────────────────────────────
    //  索引重建
    // ─────────────────────────────────────────────────────────────

    /**
     * 扫描 memoryDir 下所有 .md 文件(除索引本身),拼出索引文件:
     * 每行一条 {@code - [name](filename) — description}。
     *
     * <p>空目录时索引文件也会被写为空字符串(便于 SYSTEM 注入时一致处理)。
     */
    public void rebuildIndex() {
        try {
            Files.createDirectories(config.memoryDir());
            List<MemoryFile> mems = list();
            // list 已经按 mtime 倒序,但索引按 name 字典序更稳定 + 可读
            mems.sort(Comparator.comparing(MemoryFile::getName, Comparator.nullsLast(String::compareTo)));

            StringBuilder sb = new StringBuilder();
            for (MemoryFile m : mems) {
                if (m.getFilename() == null || m.getName() == null) continue;
                sb.append("- [").append(m.getName()).append("](")
                        .append(m.getFilename()).append(") — ")
                        .append(m.getDescription() == null ? "" : m.getDescription())
                        .append('\n');
            }
            Files.writeString(config.indexPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to rebuild index", e);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Frontmatter 工具(包级可见,给测试用)
    // ─────────────────────────────────────────────────────────────

    /** 解析一个完整 .md 文本(frontmatter + body)。*/
    static MemoryFile parse(String text, String filename) {
        Matcher m = FRONTMATTER.matcher(text);
        if (!m.find()) {
            // 没有 frontmatter:整段当 body,其它字段空
            MemoryFile mf = new MemoryFile();
            mf.setBody(text);
            mf.setFilename(filename);
            mf.setType(MemoryFile.Type.USER);
            return mf;
        }
        String frontText = m.group(1);
        String body = m.group(2);

        Map<String, String> kv = new HashMap<>();
        for (String line : frontText.split("\\r?\\n")) {
            Matcher lm = FRONTMATTER_LINE.matcher(line);
            if (lm.matches()) {
                kv.put(lm.group(1), lm.group(2));
            }
        }
        return new MemoryFile(
                kv.getOrDefault("name", ""),
                MemoryFile.Type.parse(kv.get("type")),
                kv.getOrDefault("description", ""),
                body == null ? "" : body,
                filename
        );
    }

    /** 把 MemoryFile 渲染为 frontmatter + body 文本。*/
    static String renderFrontmatter(MemoryFile mem, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(escapeYamlScalar(mem.getName())).append('\n');
        sb.append("description: ").append(escapeYamlScalar(mem.getDescription() == null ? "" : mem.getDescription())).append('\n');
        sb.append("type: ").append(mem.getType().slug()).append('\n');
        sb.append("---\n\n");
        sb.append(body == null ? "" : body);
        if (body != null && !body.endsWith("\n")) sb.append('\n');
        return sb.toString();
    }

    /**
     * YAML scalar 极简转义:把换行替换为空格,避免破坏 frontmatter 结构。
     * 教学版不做完整 YAML 转义,假设 name/description 单行。
     */
    private static String escapeYamlScalar(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private String truncateBody(String body) {
        if (body == null) return "";
        int max = config.maxBodyBytes();
        if (body.length() <= max) return body;
        return body.substring(0, max) + "...";
    }

    private void validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename must not be blank");
        }
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException("filename must not contain path separators or '..': " + filename);
        }
    }
}
