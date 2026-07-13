package com.xilidou.jooj.cron;

import com.xilidou.jooj.session.AgentLockProvider;
import com.xilidou.jooj.session.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cron 4 层架构 — Layer 3:queue processor 定时调度。
 *
 * <p>每 {@link CronConfig#processorTickMs} 毫秒(默认 200ms)轮询 queue,
 * 一旦有 fired job 就交给 {@link CronTurnOrchestrator} 处理。
 *
 * <p>对应上游 s14 {@code _process_cron_queue} 的 thread 形态。
 *
 * <h3>线程模型</h3>
 *
 * <p>线程重构后用 Spring {@code @Scheduled} 替代裸 {@code new Thread().start()},
 * 跟 {@link CronScheduler} 同模式。两个 tick 都跑在 {@code joojTaskScheduler}
 * 共享调度池,Spring 容器接管启停。
 *
 * <h3>s22 架构审查(2026-07-13)</h3>
 *
 * <p>拆解:
 * <ul>
 *   <li>本类只做"tick + drain queue + 交给 orchestrator" 的调度职责</li>
 *   <li>Turn 编排(per-session lock + processOneQuery + delivery)搬到
 *       {@link CronTurnOrchestrator}</li>
 *   <li>Delivery 分派搬到 {@link CronDeliveryHandler}</li>
 * </ul>
 *
 * <p>之前的实现在 {@code AgentLoopHarness.processCronTriggers} 里,harness
 * 因此持有 {@code channelDelivererProvider} + 大段 cron 相关逻辑。s22 架构审查
 * 借鉴 Hermes 的 {@code cron/scheduler.py} 结构,把 cron 编排从 harness 剥离。
 *
 * <h3>Cron-default lock 语义</h3>
 *
 * <p>本 tick 仍抢 {@link Session#CRON_DEFAULT_ID} 的 lock —— 但这个 lock
 * 现在只保护 "queue drain 本身",不再等同于"cron turn 期间"。Turn 期间的
 * per-session lock 由 {@link CronTurnOrchestrator#processFired} 内部按
 * {@code job.sessionId} 重新抢,让 cron turn 跟对应 session 的 web/channel
 * 请求真正互斥。
 */
@Component
@Slf4j
public class CronQueueProcessor {

    private final CronService service;
    private final CronTurnOrchestrator orchestrator;
    private final AgentLockProvider lockProvider;

    public CronQueueProcessor(CronService service,
                              CronTurnOrchestrator orchestrator,
                              AgentLockProvider lockProvider) {
        this.service = service;
        this.orchestrator = orchestrator;
        this.lockProvider = lockProvider;
    }

    /**
     * Layer 3 主 tick:每 processorTickMs 毫秒检查 cron queue。
     */
    @Scheduled(
            fixedDelayString = "${jooj.cron.processor-tick-ms:200}",
            initialDelayString = "${jooj.cron.processor-initial-delay-ms:500}"
    )
    public void tick() {
        if (!service.hasQueued()) return;
        ReentrantLock lock = lockProvider.lockFor(Session.CRON_DEFAULT_ID);
        // cron-default session 正被使用 → 不抢,等下一轮
        if (!lock.tryLock()) return;
        try {
            // 拿到锁后再 double-check,避免别的线程刚 drain 走
            if (!service.hasQueued()) return;
            List<CronJob> fired = service.drainQueue();
            if (fired.isEmpty()) return;
            log.info("[Cron] processing {} fired job(s)", fired.size());
            orchestrator.processFired(fired);
        } catch (Exception e) {
            log.warn("[Cron] processor tick failed: {}", e.toString());
        } finally {
            lock.unlock();
        }
    }
}
