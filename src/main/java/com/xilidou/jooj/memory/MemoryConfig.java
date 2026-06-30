package com.xilidou.jooj.memory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Memory 系统配置。
 *
 * <p>设计理由:跟 {@link com.xilidou.jooj.compact.CompactConfig} 一致,
 * 纯 POJO,无 Spring 依赖;测试用全参构造器覆盖默认值,生产用无参构造器。
 *
 * <p>5 个常量含义:
 * <ul>
 *   <li>{@code memoryDir=".memory"}:相对 cwd 的 memory 文件目录</li>
 *   <li>{@code indexFilename="MEMORY.md"}:索引文件名,放在 memoryDir 下</li>
 *   <li>{@code maxBodyBytes=4096}:单条 memory body 的字符数上限,
 *       防止 LLM 写出超长 body 把 SYSTEM/turn 注入挤爆</li>
 *   <li>{@code consolidateThreshold=10}:memory 文件数 > 此阈值时
 *       触发 Consolidator(留给后续 session)</li>
 *   <li>{@code totalMaxBytes=20000}:所有 memory 文件 body 字符数之和上限。
 *       (s21 Demo 21,对齐 Hermes ~1300 token 配额哲学)
 *       超过时 {@link MemoryStore#write} 拒写并返回带 usage/limit 的可读 error,
 *       逼 LLM 主动 replace/remove 而不是无脑 add。jooj 走"目录+索引"模式,
 *       per-entry 模式占字符更多,所以放大到 20K(Hermes 平铺单文件 2200+1375)。</li>
 * </ul>
 */
public class MemoryConfig {

    private final Path memoryDir;
    private final String indexFilename;
    private final int maxBodyBytes;
    private final int consolidateThreshold;
    private final int totalMaxBytes;

    /** 默认值构造器(生产用):cwd/.memory/。*/
    public MemoryConfig() {
        this(defaultMemoryDir(), "MEMORY.md", 4096, 10, 20000);
    }

    /**
     * 4 参构造器(向后兼容 Demo 21 之前,totalMaxBytes 走默认 20000)。
     */
    public MemoryConfig(Path memoryDir, String indexFilename,
                        int maxBodyBytes, int consolidateThreshold) {
        this(memoryDir, indexFilename, maxBodyBytes, consolidateThreshold, 20000);
    }

    /** 全参构造器(测试 / 自定义)。*/
    public MemoryConfig(Path memoryDir, String indexFilename,
                        int maxBodyBytes, int consolidateThreshold,
                        int totalMaxBytes) {
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
        if (totalMaxBytes <= 0) {
            throw new IllegalArgumentException("totalMaxBytes must be > 0; got " + totalMaxBytes);
        }
        this.memoryDir = memoryDir;
        this.indexFilename = indexFilename;
        this.maxBodyBytes = maxBodyBytes;
        this.consolidateThreshold = consolidateThreshold;
        this.totalMaxBytes = totalMaxBytes;
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

    /**
     * 所有 memory body 字符数之和上限(s21 Demo 21)。
     *
     * <p>{@link MemoryStore#totalBodyChars()} 累加所有 memory 的 body 字符,
     * 超过这里的上限后 {@link MemoryStore#write} 拒写并返回带 used/limit 的
     * 可读错误,逼 LLM 主动 replace/remove 而不是无脑 add。
     *
     * <p>不算 frontmatter / index —— 它们对 LLM 不可见配额,只算"真信息密度"。
     */
    public int totalMaxBytes() {
        return totalMaxBytes;
    }
}
