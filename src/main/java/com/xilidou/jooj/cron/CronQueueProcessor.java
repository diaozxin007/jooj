package com.xilidou.jooj.cron;

import com.xilidou.jooj.agent.AgentLoopHarness;
import com.xilidou.jooj.session.AgentLockProvider;
import com.xilidou.jooj.session.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cron 4 层架构 — Layer 3:queue processor 定时调度。
 *
 * <p>每 {@link CronConfig#processorTickMs} 毫秒(默认 200ms)轮询 queue,
 * 一旦有 fired job 且能拿到 {@code cron-default session} 的 lock,就触发一轮 agent_loop。
 *
 * <p>对应上游 s14 {@code _process_cron_queue} 的 thread 形态。
 *
 * <h3>线程模型</h3>
 *
 * <p>线程重构后用 Spring {@code @Scheduled} 替代裸 {@code new Thread().start()},
 * 跟 {@link CronScheduler} 同模式。两个 tick 都跑在 {@code joojTaskScheduler}
 * 共享调度池,Spring 容器接管启停。
 *
 * <h3>循环依赖打破:{@code @Lazy}</h3>
 *
 * <p>本类需要 {@link AgentLoopHarness},但 {@link AgentLoopHarness#agentLoop}
 * 本身要 {@code drainQueue},如果直接构造注入会成环。
 *
 * <h3>Session 抽象后的锁语义</h3>
 *
 * <p>cron 触发的 LLM run 路由到 {@link Session#CRON_DEFAULT_ID} session
 * (见 {@link AgentLoopHarness#processCronTriggers})。
 * 本 tick 抢的也是这个 session 的 lock —— 用户跟 cron-default session 交互时
 * (理论上不应该,但 web 给了 API 能走到),会跟 cron 互斥。
 *
 * <p>用户跟别的 session 对话时,cron 的 {@code cron-default} lock 完全独立,
 * 不会拖累 —— 这正是引入 session 抽象后想要的隔离。
 */
@Component
@Slf4j
public class CronQueueProcessor {

    private final CronService service;
    private final AgentLoopHarness harness;
    private final AgentLockProvider lockProvider;

    public CronQueueProcessor(CronService service,
                              @Lazy AgentLoopHarness harness,
                              AgentLockProvider lockProvider) {
        this.service = service;
        this.harness = harness;
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
            harness.processCronTriggers(fired);
        } catch (Exception e) {
            log.warn("[Cron] processor tick failed: {}", e.toString());
        } finally {
            lock.unlock();
        }
    }
}
