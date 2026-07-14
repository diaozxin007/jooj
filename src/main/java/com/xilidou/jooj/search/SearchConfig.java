package com.xilidou.jooj.search;

import java.nio.file.Path;

/**
 * SearchStore 的纯 POJO 配置 —— 不依赖 Spring,测试可直接 new。
 *
 * <p>对应 {@link com.xilidou.jooj.search.SearchProperties} 的 yml 字段,但保留独立的纯 POJO
 * 是为了让 {@link SearchStore} / {@link SearchService} 在测试里 {@code new SearchConfig(...)}
 * 不依赖 Spring 容器 —— 跟 {@code MemoryConfig} / {@code TaskConfig} 同模式。
 *
 * @param dbPath        SQLite 数据库文件绝对路径(包含目录),由 SearchConfiguration 拼出
 * @param schemaVersion FTS5 schema 版本,startup check 用来对照 schema_meta.version
 * @param defaultLimit  session_search tool 默认 limit
 * @param maxLimit      session_search tool 最大 limit(LLM 传超过此值 clamp 到此)
 * @param busyTimeoutMs SQLite busy_timeout PRAGMA 值,WAL 模式下基本用不到
 * @param startupCheck  {@code none / light / strict} 三档 —— 见 {@code SearchProperties.startupCheck}
 */
public record SearchConfig(
        Path dbPath,
        int schemaVersion,
        int defaultLimit,
        int maxLimit,
        int busyTimeoutMs,
        String startupCheck
) {
    public SearchConfig {
        if (dbPath == null) throw new IllegalArgumentException("dbPath must not be null");
        if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be > 0");
        if (defaultLimit <= 0) throw new IllegalArgumentException("defaultLimit must be > 0");
        if (maxLimit <= 0) throw new IllegalArgumentException("maxLimit must be > 0");
        if (defaultLimit > maxLimit) {
            throw new IllegalArgumentException(
                    "defaultLimit (" + defaultLimit + ") must not exceed maxLimit (" + maxLimit + ")");
        }
        if (busyTimeoutMs < 0) throw new IllegalArgumentException("busyTimeoutMs must be >= 0");
        if (startupCheck == null || startupCheck.isBlank()) {
            startupCheck = "light";
        }
    }
}
