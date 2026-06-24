package com.xilidou.marvis.memory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Memory 系统配置。
 *
 * <p>设计理由:跟 {@link com.xilidou.marvis.compact.CompactConfig} 一致,
 * 纯 POJO,无 Spring 依赖;测试用全参构造器覆盖默认值,生产用无参构造器。
 *
 * <p>4 个常量含义:
 * <ul>
 *   <li>{@code memoryDir=".memory"}:相对 cwd 的 memory 文件目录</li>
 *   <li>{@code indexFilename="MEMORY.md"}:索引文件名,放在 memoryDir 下</li>
 *   <li>{@code maxBodyBytes=4096}:单条 memory body 的字符数上限,
 *       防止 LLM 写出超长 body 把 SYSTEM/turn 注入挤爆</li>
 *   <li>{@code consolidateThreshold=10}:memory 文件数 > 此阈值时
 *       触发 Consolidator(留给后续 session)</li>
 * </ul>
 */
public class MemoryConfig {

    private final Path memoryDir;
    private final String indexFilename;
    private final int maxBodyBytes;
    private final int consolidateThreshold;

    /** 默认值构造器(生产用):cwd/.memory/。*/
    public MemoryConfig() {
        this(defaultMemoryDir(), "MEMORY.md", 4096, 10);
    }

    /** 全参构造器(测试 / 自定义)。*/
    public MemoryConfig(Path memoryDir, String indexFilename,
                        int maxBodyBytes, int consolidateThreshold) {
        if (memoryDir == null) {
            throw new IllegalArgumentException("memoryDir must not be null");
        }
        if (indexFilename == null || indexFilename.isBlank()) {
            throw new IllegalArgumentException("indexFilename must not be blank");
        }
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be > 0; got " + maxBodyBytes);
        }
        if (consolidateThreshold <= 0) {
            throw new IllegalArgumentException("consolidateThreshold must be > 0; got " + consolidateThreshold);
        }
        this.memoryDir = memoryDir;
        this.indexFilename = indexFilename;
        this.maxBodyBytes = maxBodyBytes;
        this.consolidateThreshold = consolidateThreshold;
    }

    private static Path defaultMemoryDir() {
        return Paths.get(System.getProperty("user.dir"), ".memory");
    }

    public Path memoryDir() {
        return memoryDir;
    }

    public String indexFilename() {
        return indexFilename;
    }

    /** 索引文件全路径。*/
    public Path indexPath() {
        return memoryDir.resolve(indexFilename);
    }

    public int maxBodyBytes() {
        return maxBodyBytes;
    }

    public int consolidateThreshold() {
        return consolidateThreshold;
    }
}
