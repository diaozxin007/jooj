package com.xilidou.marvis.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link CronStore} 的文件 I/O 行为。
 *
 * <p>跟 {@link com.xilidou.marvis.tasks.TaskStore} 的测试同模式 ——
 * 用 {@code @TempDir} 隔离磁盘,直接 {@code new} 不走 Spring。
 */
class CronStoreTest {

    @TempDir
    Path tempDir;

    private CronStore store;
    private Path durableFile;

    @BeforeEach
    void setUp() {
        ObjectMapper json = JacksonConfig.newMapper();
        durableFile = tempDir.resolve(".scheduled_tasks.json");
        CronConfig config = new CronConfig(durableFile, 1000L, 200L);
        store = new CronStore(config, json);
    }

    @Test
    @DisplayName("save / load roundtrip: list 内容应该完全一致")
    void save_load_roundtrip() {
        CronJob a = new CronJob("cron_000001", "0 9 * * *", "do A", true, true);
        CronJob b = new CronJob("cron_000002", "*/5 * * * *", "do B", false, true);
        store.save(List.of(a, b));

        List<CronJob> loaded = store.load();
        assertEquals(2, loaded.size());
        assertEquals("cron_000001", loaded.get(0).getId());
        assertEquals("0 9 * * *", loaded.get(0).getCron());
        assertEquals("do A", loaded.get(0).getPrompt());
        assertTrue(loaded.get(0).isRecurring());
        assertTrue(loaded.get(0).isDurable());

        assertEquals("cron_000002", loaded.get(1).getId());
        assertFalse(loaded.get(1).isRecurring());
    }

    @Test
    @DisplayName("不存在文件时 load 返回空 list,不抛")
    void load_missing_file_returns_empty() {
        // setUp 还没 save 过,文件不存在
        List<CronJob> loaded = store.load();
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }

    @Test
    @DisplayName("损坏文件 load 不抛 — 返空 list + warn")
    void load_corrupt_file_returns_empty() throws IOException {
        // 写一个非法 JSON
        Files.writeString(durableFile, "{ bad json }", StandardCharsets.UTF_8);

        List<CronJob> loaded = store.load();
        assertNotNull(loaded, "损坏文件 load 不应该抛异常");
        assertTrue(loaded.isEmpty());
    }

    @Test
    @DisplayName("save 空 list 也写入空 JSON 数组")
    void save_empty_writes_empty_array() throws IOException {
        store.save(List.of());
        assertTrue(Files.exists(durableFile));
        String content = Files.readString(durableFile, StandardCharsets.UTF_8);
        assertTrue(content.contains("[ ]") || content.equals("[]") || content.trim().equals("[]"),
                "空 list 应写入空 JSON 数组,实际:" + content);
    }
}
