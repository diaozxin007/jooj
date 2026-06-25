package com.xilidou.marvis.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link CronService} 的核心业务逻辑。
 *
 * <p>覆盖:
 * <ul>
 *   <li>schedule / cancel / list 各路径</li>
 *   <li>fireMatching:同分钟去重 + 跨分钟可重 fire + recurring 行为</li>
 *   <li>durable 启动恢复</li>
 * </ul>
 *
 * <p>纯单测,不起 thread,直接调 {@link CronService#fireMatching} 模拟"scheduler tick"。
 */
class CronServiceTest {

    @TempDir
    Path tempDir;

    private CronService service;
    private CronStore store;

    @BeforeEach
    void setUp() {
        ObjectMapper json = JacksonConfig.newMapper();
        Path durable = tempDir.resolve(".scheduled_tasks.json");
        CronConfig cfg = new CronConfig(durable, 1000L, 200L);
        store = new CronStore(cfg, json);
        service = new CronService(store);
    }

    // ─────────────────────────────────────────────────────────────
    //  schedule / list / cancel
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("schedule 返回 cron_<6位> id;list 包含")
    void schedule_returns_id_and_list_contains() {
        String id = service.schedule("0 9 * * *", "do X", true, false);
        assertTrue(id.startsWith("cron_"), "id 应以 cron_ 开头,实际:" + id);
        assertEquals(11, id.length(), "id 形如 cron_XXXXXX,长度 11");

        List<CronJob> jobs = service.list();
        assertEquals(1, jobs.size());
        assertEquals(id, jobs.get(0).getId());
        assertEquals("0 9 * * *", jobs.get(0).getCron());
        assertEquals("do X", jobs.get(0).getPrompt());
    }

    @Test
    @DisplayName("schedule 失败的 cron → 返回 'Error: ...',scheduled 里没加进去")
    void schedule_invalid_cron_returns_error() {
        String result = service.schedule("60 * * * *", "do X", true, false);
        assertTrue(result.startsWith("Error:"), "实际:" + result);
        assertEquals(0, service.scheduledCount());
    }

    @Test
    @DisplayName("schedule 空 prompt → 'Error: prompt must not be blank'")
    void schedule_empty_prompt_returns_error() {
        String result = service.schedule("0 9 * * *", "", true, false);
        assertTrue(result.contains("prompt"), "实际:" + result);
        assertEquals(0, service.scheduledCount());
    }

    @Test
    @DisplayName("cancel 存在的 id → 'Cancelled <id>';不存在的 id → 'Job <id> not found'")
    void cancel_paths() {
        String id = service.schedule("0 9 * * *", "x", true, false);
        assertEquals("Cancelled " + id, service.cancel(id));
        assertEquals(0, service.scheduledCount());

        assertTrue(service.cancel("cron_999999").contains("not found"));
    }

    // ─────────────────────────────────────────────────────────────
    //  fireMatching
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fireMatching:匹配的 job 入队,不匹配的不动")
    void fire_matching_enqueues_matched() {
        String idA = service.schedule("0 9 * * *", "morning", true, false);
        service.schedule("0 18 * * *", "evening", true, false);

        service.fireMatching(LocalDateTime.of(2026, 6, 25, 9, 0));
        assertEquals(1, service.queueSize());

        List<CronJob> drained = service.drainQueue();
        assertEquals(1, drained.size());
        assertEquals(idA, drained.get(0).getId());
    }

    @Test
    @DisplayName("fireMatching:同一分钟内只入队一次(lastFired marker 防重复)")
    void fire_matching_dedupes_same_minute() {
        service.schedule("0 9 * * *", "x", true, false);

        LocalDateTime t = LocalDateTime.of(2026, 6, 25, 9, 0);
        service.fireMatching(t);
        service.fireMatching(t);
        service.fireMatching(t);

        assertEquals(1, service.queueSize(), "同分钟多次 fireMatching 只入队一次");
    }

    @Test
    @DisplayName("fireMatching:跨分钟可再次 fire")
    void fire_matching_re_fires_next_minute() {
        service.schedule("* * * * *", "every minute", true, false);

        service.fireMatching(LocalDateTime.of(2026, 6, 25, 9, 0));
        service.fireMatching(LocalDateTime.of(2026, 6, 25, 9, 1));

        assertEquals(2, service.queueSize());
    }

    @Test
    @DisplayName("fireMatching:跨天可再次 fire(同 HH:mm 但日期变)")
    void fire_matching_re_fires_next_day() {
        // 上游踩过的坑:lastFired marker 不带日期会让 23:59 后的次日 同时刻 误判为已 fire
        service.schedule("0 9 * * *", "daily", true, false);

        service.fireMatching(LocalDateTime.of(2026, 6, 25, 9, 0));
        service.fireMatching(LocalDateTime.of(2026, 6, 26, 9, 0)); // 次日同时刻

        assertEquals(2, service.queueSize(), "跨天同时刻应再次 fire");
    }

    @Test
    @DisplayName("recurring=false 的 job fire 后从 scheduled 移除")
    void one_shot_removed_after_fire() {
        String id = service.schedule("0 9 * * *", "once", false, false);
        assertEquals(1, service.scheduledCount());

        service.fireMatching(LocalDateTime.of(2026, 6, 25, 9, 0));
        assertEquals(1, service.queueSize(), "队列里有 1 条");
        assertEquals(0, service.scheduledCount(), "scheduled 应被移除");

        // 再 fire 一次也无效 —— 已经不在 scheduled 里
        service.fireMatching(LocalDateTime.of(2026, 6, 26, 9, 0));
        assertEquals(1, service.queueSize());

        // 取消已不存在的应该报 not found
        assertTrue(service.cancel(id).contains("not found"));
    }

    @Test
    @DisplayName("recurring=true 的 job 永远不从 scheduled 移除")
    void recurring_persists_after_fire() {
        String id = service.schedule("0 9 * * *", "daily", true, false);

        service.fireMatching(LocalDateTime.of(2026, 6, 25, 9, 0));
        service.drainQueue();
        service.fireMatching(LocalDateTime.of(2026, 6, 26, 9, 0));

        assertEquals(1, service.scheduledCount());
        assertEquals(id, service.list().get(0).getId());
    }

    // ─────────────────────────────────────────────────────────────
    //  durable 启动恢复
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("durable=true 写盘,新 service 实例(模拟重启)能恢复")
    void durable_persisted_and_recovered() {
        String id = service.schedule("0 9 * * *", "persistent", true, true);

        // 模拟重启:new service 同一个 store,应自动从盘恢复
        CronService restarted = new CronService(store);
        List<CronJob> jobs = restarted.list();
        assertEquals(1, jobs.size());
        assertEquals(id, jobs.get(0).getId());
        assertTrue(jobs.get(0).isDurable());
    }

    @Test
    @DisplayName("durable=false 不写盘,重启后丢")
    void non_durable_not_persisted() {
        service.schedule("0 9 * * *", "transient", true, false);

        CronService restarted = new CronService(store);
        assertEquals(0, restarted.scheduledCount());
    }

    @Test
    @DisplayName("cancel durable=true 后,文件里也要消失")
    void cancel_durable_updates_file() {
        String id = service.schedule("0 9 * * *", "x", true, true);
        service.cancel(id);

        CronService restarted = new CronService(store);
        assertEquals(0, restarted.scheduledCount());
    }
}
