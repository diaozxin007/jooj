package com.xilidou.jooj.cron;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Cron Job 文件存储层 —— 跟 {@link com.xilidou.jooj.tasks.TaskStore} 同设计模式,
 * 但磁盘格式不同。
 *
 * <p>对应上游 s14 {@code _save_durable_jobs / _load_durable_jobs}。
 *
 * <h3>磁盘格式</h3>
 *
 * <p>单文件 JSON 数组(<b>不是</b>每条一个文件 —— 跟上游一致):
 * <pre>
 *   .scheduled_tasks.json:
 *   [
 *     {"id": "cron_123456", "cron": "0 9 * * *", "prompt": "...",
 *      "recurring": true, "durable": true},
 *     {"id": "cron_654321", ...}
 *   ]
 * </pre>
 *
 * <p>只持久化 {@code durable=true} 的 job —— {@link CronService#schedule}
 * 把这个责任传到本类:调用方过滤后再传 list。本类<b>不</b>做过滤。
 *
 * <h3>线程安全</h3>
 *
 * <p>不保证。CronService 持有 service 级别的同步,本类是纯文件 I/O。
 *
 * <h3>容错</h3>
 *
 * <p>{@link #load} 文件不存在 → 空 list;损坏文件 → 空 list + warn。
 * 不抛异常 —— 上游 README 强调 fail-soft,jooj 不该因为一个 corrupt JSON 启动失败。
 */
@Slf4j
public class CronStore {

    private final CronConfig config;
    private final ObjectMapper json;

    public CronStore(CronConfig config, ObjectMapper json) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (json == null) throw new IllegalArgumentException("json must not be null");
        this.config = config;
        this.json = json;
    }

    /**
     * 写盘 —— 整个 list 以 JSON array 形式写入 durable 文件。
     *
     * <p>调用方应只传 {@code durable=true} 的 job 子集;本类不过滤。
     * 空 list 写入空数组(也会落盘,跟上游一致 —— 让旧持久化记录被清掉)。
     */
    public void save(List<CronJob> jobs) {
        if (jobs == null) jobs = List.of();
        try {
            Path file = config.durablePath();
            Files.createDirectories(file.toAbsolutePath().getParent());
            String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(jobs);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            log.info("[Cron] saved {} durable job(s) to {}", jobs.size(), file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save cron jobs", e);
        }
    }

    /**
     * 加载 —— 文件不存在或解析失败时返空 list 而不抛(fail-soft)。
     *
     * <p>上游 {@code _load_durable_jobs} 启动时调一次:
     * jooj 进程启动 → CronService 构造时调本方法 → 把已恢复的 job 注册回内存 dict。
     *
     * @return 已恢复的 job 列表(可能为空)
     */
    public List<CronJob> load() {
        Path file = config.durablePath();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) return new ArrayList<>();
            List<CronJob> jobs = json.readValue(text, new TypeReference<>() {});
            return jobs != null ? jobs : new ArrayList<>();
        } catch (NoSuchFileException e) {
            return new ArrayList<>();
        } catch (Exception e) {
            log.warn("[Cron] failed to load {}: {} — starting with empty job list",
                    file, e.toString());
            return new ArrayList<>();
        }
    }
}
