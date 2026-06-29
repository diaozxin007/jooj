package com.xilidou.jooj.cron;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cron Job 业务封装 —— 把 {@link CronStore} 文件 I/O 提升为业务语义,
 * 同时承载 {@link #schedule} / {@link #cancel} / {@link #fireMatching} 等
 * 关键逻辑。
 *
 * <p>对应上游 s14 的 {@code schedule_cron / cancel_cron / list_crons /
 * _check_and_fire_jobs / _scheduler_loop} 中的业务部分(thread 部分见 {@link CronScheduler})。
 *
 * <h3>4 层架构里的位置</h3>
 *
 * <p>本类是<b>无 thread</b> 的业务 API。它持有:
 * <ul>
 *   <li>{@link #scheduled} —— 所有已调度 job 的 dict(id → CronJob)</li>
 *   <li>{@link #queue} —— Layer 2 触发队列,scheduler 写,processor 读</li>
 *   <li>{@link #lastFired} —— 防同分钟重复触发的标记(带日期防跨天误判)</li>
 * </ul>
 *
 * <p>thread 不在本类:
 * <ul>
 *   <li>Layer 1 = {@link CronScheduler}(daemon thread,1s tick,调 {@link #fireMatching})</li>
 *   <li>Layer 3 = {@code CronQueueProcessor}(daemon thread,200ms tick,
 *       拿 agentLock 后调 {@link #drainQueue})</li>
 * </ul>
 *
 * <h3>错误返回是字符串(NL),不抛异常</h3>
 *
 * <p>跟 {@link com.xilidou.jooj.tasks.TaskService} 同模式 ——
 * {@link #schedule} / {@link #cancel} 失败时返回人类可读字符串
 * (如 {@code "Error: <msg>"} 或 {@code "Job <id> not found"}),
 * 让 LLM 看到字符串后自我纠正。
 */
@Slf4j
public class CronService {

    /** lastFired marker 的时间格式 —— 必须含日期,否则 23:59 / 次日 00:01 同 minute 字段会误判。 */
    private static final DateTimeFormatter MINUTE_MARKER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CronStore store;

    /** 所有已调度 job(包括 durable + 非 durable)。 */
    private final Map<String, CronJob> scheduled = new ConcurrentHashMap<>();

    /** Layer 2:scheduler 写 / processor 读的触发队列。 */
    private final ConcurrentLinkedQueue<CronJob> queue = new ConcurrentLinkedQueue<>();

    /** job_id → 上次 fire 的"yyyy-MM-dd HH:mm"标记,防同分钟重复。 */
    private final Map<String, String> lastFired = new ConcurrentHashMap<>();

    public CronService(CronStore store) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        this.store = store;
        // 启动恢复:把 durable=true 的 job 加载回 scheduled dict。
        // 跟上游 s14 启动时调 _load_durable_jobs 一致。
        List<CronJob> recovered = store.load();
        for (CronJob job : recovered) {
            scheduled.put(job.getId(), job);
        }
        if (!recovered.isEmpty()) {
            log.info("[Cron] loaded {} durable job(s) from disk", recovered.size());
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  schedule / cancel / list
    // ─────────────────────────────────────────────────────────────

    /**
     * 调度一个新 cron job。返回新分配的 id 或 NL 错误。
     *
     * <p>失败语义(返回字符串以 {@code "Error: "} 开头):
     * <ul>
     *   <li>cron 表达式非法 → {@code "Error: <CronExpression.validate 错误>"}</li>
     *   <li>prompt 为空 → {@code "Error: prompt must not be blank"}</li>
     * </ul>
     *
     * <p>id 格式:{@code cron_<6 位随机数>},跟上游
     * {@code f"cron_{random.randint(0, 999999):06d}"} 一致。
     *
     * @return 成功时 6 位 id;失败时 {@code "Error: ..."} 字符串
     */
    public String schedule(String cron, String prompt, boolean recurring, boolean durable) {
        return schedule(cron, prompt, recurring, durable, null);
    }

    /**
     * s20 Demo 9:带 sessionId 的 schedule —— fire 时把 prompt 注入这个 session,而不是
     * 一律塞 cron-default。{@code sessionId == null} 等价于老行为(注入 cron-default)。
     */
    public String schedule(String cron, String prompt, boolean recurring, boolean durable,
                           String sessionId) {
        if (prompt == null || prompt.isBlank()) {
            return "Error: prompt must not be blank";
        }
        String validation = CronExpression.validate(cron);
        if (validation != null) return validation;

        String id = generateId();
        CronJob job = new CronJob(id, cron, prompt, recurring, durable, sessionId);
        scheduled.put(id, job);
        if (durable) persistDurable();
        log.info("[Cron] scheduled {} '{}' → '{}' (recurring={}, durable={}, session={})",
                id, cron, prompt, recurring, durable, sessionId);
        return id;
    }

    /**
     * 取消一个已调度 job。
     *
     * @return 成功时 {@code "Cancelled <id>"};id 不存在时 {@code "Job <id> not found"}
     */
    public String cancel(String id) {
        if (id == null || !scheduled.containsKey(id)) {
            return "Job " + id + " not found";
        }
        CronJob removed = scheduled.remove(id);
        lastFired.remove(id);
        // 如果是 durable,回写文件
        if (removed != null && removed.isDurable()) persistDurable();
        log.info("[Cron] cancelled {}", id);
        return "Cancelled " + id;
    }

    /**
     * 列出所有已调度 job —— 按 id 字典序排序的拷贝(防外部 mutate)。
     */
    public List<CronJob> list() {
        List<CronJob> out = new ArrayList<>(scheduled.values());
        out.sort(Comparator.comparing(CronJob::getId));
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    //  Queue ops(Layer 2 ↔ Layer 3 接口)
    // ─────────────────────────────────────────────────────────────

    /** 当前队列是否非空 —— processor 用此快速短路。 */
    public boolean hasQueued() {
        return !queue.isEmpty();
    }

    /**
     * 把队列里所有 job 一次性 drain 出来 —— 由 agent_loop 顶部消费。
     *
     * <p>每次 agent_loop 进入时 drain 一次,把所有 fired job 转成 user message 注入。
     */
    public List<CronJob> drainQueue() {
        if (queue.isEmpty()) return Collections.emptyList();
        List<CronJob> out = new ArrayList<>();
        CronJob job;
        while ((job = queue.poll()) != null) out.add(job);
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    //  fireMatching —— Layer 1 调用
    // ─────────────────────────────────────────────────────────────

    /**
     * 扫所有 job,把匹配 {@code now} 且本分钟未 fire 过的入队。
     *
     * <p>对应上游 {@code _check_and_fire_jobs}:
     * <ol>
     *   <li>遍历 {@link #scheduled}</li>
     *   <li>对每条调 {@link CronExpression#matches} 看本分钟是否触发</li>
     *   <li>若 {@link #lastFired} 标记 ==当前 {@code "yyyy-MM-dd HH:mm"},跳过(防重复)</li>
     *   <li>否则入队 + 更新 lastFired</li>
     *   <li>recurring=false 的 job fire 后从 scheduled 移除(并写回 durable 文件)</li>
     * </ol>
     *
     * <p><b>跨天保护</b>:lastFired marker 带日期(2026-06-25 09:00)而不只是
     * {@code HH:mm} —— 否则 23:59 fire 后,次日 00:01 因为 {@code HH:mm != "23:59"}
     * 会误判为"已 fire 过本分钟"...wait,实际相反:不带日期会让"每天 9 点"的 job
     * 在凌晨跨天后下次 9 点重新 fire 时,如果 marker 还是上次的 "09:00",会误判
     * 为同一分钟。带日期 "2026-06-25 09:00" 让前一天的 marker 自然失效。
     *
     * @param now 当前时间(让外部传入便于测试)
     */
    public void fireMatching(LocalDateTime now) {
        String currentMarker = now.format(MINUTE_MARKER);
        List<String> toRemove = new ArrayList<>();
        boolean durableChanged = false;

        for (CronJob job : scheduled.values()) {
            boolean matches;
            try {
                matches = CronExpression.matches(job.getCron(), now);
            } catch (Exception e) {
                // 损坏的 cron 表达式不应该让 scheduler thread 死,跳过 + warn
                log.warn("[Cron] invalid expression in {}: {} ({})",
                        job.getId(), job.getCron(), e.toString());
                continue;
            }
            if (!matches) continue;

            String prevMarker = lastFired.get(job.getId());
            if (currentMarker.equals(prevMarker)) {
                // 同一分钟内已经 fire 过 —— 跳过
                continue;
            }

            // fire!
            queue.offer(job);
            lastFired.put(job.getId(), currentMarker);
            log.info("[Cron] fired {} (cron='{}', prompt='{}')",
                    job.getId(), job.getCron(), job.getPrompt());

            // 一次性 job:fire 后从 scheduled 移除
            if (!job.isRecurring()) {
                toRemove.add(job.getId());
                if (job.isDurable()) durableChanged = true;
            }
        }

        for (String id : toRemove) {
            scheduled.remove(id);
            lastFired.remove(id);
        }
        if (durableChanged) persistDurable();
    }

    // ─────────────────────────────────────────────────────────────
    //  internals
    // ─────────────────────────────────────────────────────────────

    /**
     * 生成新 cron id。包级可见,允许测试通过反射或子类化时观察。
     *
     * <p>格式:{@code cron_<6位随机数>},跟上游
     * {@code f"cron_{random.randint(0, 999999):06d}"} 一致。
     */
    String generateId() {
        int rand = ThreadLocalRandom.current().nextInt(1_000_000);
        return String.format("cron_%06d", rand);
    }

    /** 把 durable=true 的 job 子集写盘,丢弃 non-durable。 */
    private void persistDurable() {
        List<CronJob> durables = new ArrayList<>();
        for (CronJob j : scheduled.values()) {
            if (j.isDurable()) durables.add(j);
        }
        store.save(durables);
    }

    // ─────────────────────────────────────────────────────────────
    //  test 钩子
    // ─────────────────────────────────────────────────────────────

    /** 测试用:把 lastFired 标记清掉,强制下次 fireMatching 重 fire。 */
    void clearLastFired() {
        lastFired.clear();
    }

    /** 测试用 / 监控用:scheduled 总数。 */
    public int scheduledCount() {
        return scheduled.size();
    }

    /** 测试用 / 监控用:queue 当前长度。 */
    public int queueSize() {
        return queue.size();
    }
}
