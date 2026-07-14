package com.xilidou.jooj.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.bootstrap.JoojHome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 读写 {@code ~/.jooj/mcp-servers/<name>.json} 的持久化助手 —— 纯 I/O,不缓存。
 *
 * <p>参考 Skill 蓝本(每 skill 一 SKILL.md)+ {@link com.xilidou.jooj.tasks.TaskStore}
 * (每 task 一 JSON):每 server 一 JSON,便于 diff / 备份 / 一键分享。
 *
 * <h3>路径</h3>
 *
 * <p>目录来自 {@link JoojHome#ensureSubdir}(默认 {@code ~/.jooj/mcp-servers/},支持
 * {@code JOOJ_HOME} 环境变量 override 用于测试隔离)。
 *
 * <h3>路径穿越防御</h3>
 *
 * <p>{@link #validateName} 拒绝 {@code /} {@code \} {@code ..} 或空字符串,防止 name 被拼路径时越权。
 *
 * <h3>线程安全</h3>
 *
 * <p>本类无状态,不保证并发访问同一 name 时的原子性。由 {@link McpServerRegistry} 通过
 * {@code synchronized} 保护并发写入。
 *
 * <h3>原子写</h3>
 *
 * <p>{@link #save} 先写 {@code <name>.json.tmp} 再 {@link StandardCopyOption#ATOMIC_MOVE}
 * rename 覆盖,防止进程崩溃时留下半写文件。ATOMIC_MOVE 在同一文件系统内是 POSIX 保证。
 *
 * <h3>历史</h3>
 *
 * <p>M1 (2026-07-14):新增,与 {@link McpServerRegistry} 一起承担 MCP 运行时加载。
 */
@Component
@Slf4j
public class McpServersJsonStore {

    /** 合法 name:字母、数字、underscore、dash,防路径穿越。 */
    private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z0-9_-]+");

    private final Path dir;
    private final ObjectMapper json;

    public McpServersJsonStore(@Qualifier("joojObjectMapper") ObjectMapper json) throws IOException {
        if (json == null) throw new IllegalArgumentException("json must not be null");
        this.json = json;
        this.dir = JoojHome.ensureSubdir(JoojHome.getHomePath(), "mcp-servers");
    }

    /** 扫目录,反序列化所有 {@code *.json} → {@link McpServerRecord} 列表。损坏文件 log.warn 跳过。 */
    public List<McpServerRecord> loadAll() {
        List<McpServerRecord> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                try {
                    McpServerRecord record = json.readValue(file.toFile(), McpServerRecord.class);
                    out.add(record);
                    log.debug("Loaded MCP server '{}' from {}", record.name(), file.getFileName());
                } catch (IOException e) {
                    log.warn("Failed to load MCP server from {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan mcp-servers dir {}: {}", dir, e.getMessage());
        }
        return out;
    }

    /**
     * 原子写入 —— 先写 tmp 再 rename,防止进程崩溃留下半写文件。
     *
     * @throws IOException 序列化 / 写盘失败
     * @throws IllegalArgumentException name 含非法字符
     */
    public void save(McpServerRecord record) throws IOException {
        if (record == null) throw new IllegalArgumentException("record must not be null");
        validateName(record.name());
        Path target = dir.resolve(record.name() + ".json");
        Path tmp = dir.resolve(record.name() + ".json.tmp");
        json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), record);
        Files.move(tmp, target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** 删除 —— name 不存在时 no-op。 */
    public void delete(String name) throws IOException {
        validateName(name);
        Files.deleteIfExists(dir.resolve(name + ".json"));
    }

    /** 目录路径(测试用)。 */
    public Path getDir() {
        return dir;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("server name must not be blank");
        }
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "server name '" + name + "' contains illegal chars (allowed: a-zA-Z0-9_-)");
        }
    }
}
