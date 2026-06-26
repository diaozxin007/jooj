package com.xilidou.jooj.cron;

import com.xilidou.jooj.agent.AgentLoopHarness;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cron 4 层架构 — Layer 3:queue processor 定时调度。
 *
 * <p>每 {@link CronConfig#processorTickMs} 毫秒(默认 200ms)轮询 queue,
 * 一旦有 fired job 且能拿到 {@code agentLock},就触发一轮 agent_loop。
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
 * 本身要 {@code drainQueue},如果直接构造注入会成环:
 * {@code CronQueueProcessor → AgentLoopHarness → ToolRegistry → CronTool → CronService}
 * (CronService 是 CronTool 持有的)。
 *
 * <p>解法跟 {@link com.xilidou.jooj.tool.impl.TaskTool} 一样用 {@code @Lazy}:
 * Spring 给本类注入一个 {@link AgentLoopHarness} 的代理,首次调用方法时才解析真实 Bean。
 *
 * <h3>agentLock 设计</h3>
 *
 * <p>{@link ReentrantLock} 是个 Spring {@code @Bean} 单例。两个地方拿这个锁:
 * <ul>
 *   <li>REPL 用户输入流程(在 {@link AgentLoopHarness#repl})</li>
 *   <li>本类 cron 触发流程(在 {@link #tick})</li>
 * </ul>
 *
 * <p>用户正在跟 jooj 对话时,cron 触发要等用户那一轮结束(用 {@link ReentrantLock#tryLock}
 * 不阻塞)。这样避免 cron-fired turn 跟 user-input turn 抢 messages list 撞车。
 */
@Component
@Slf4j
public class CronQueueProcessor {

    private final CronService service;
    private final AgentLoopHarness harness;
    private final ReentrantLock agentLock;

    public CronQueueProcessor(CronService service,
                              @Lazy AgentLoopHarness harness,
                              @Qualifier("agentLock") ReentrantLock agentLock) {
        this.service = service;
        this.harness = harness;
        this.agentLock = agentLock;
    }

    /**
     * Layer 3 主 tick:每 processorTickMs 毫秒检查 cron queue。
     *
     * <p>逻辑:
     * <ol>
     *   <li>queue 空 → 跳过</li>
     *   <li>用户输入流程正在跑(agentLock 持有)→ 跳过等下次</li>
     *   <li>否则 drain queue,触发 {@link AgentLoopHarness#processCronTriggers}</li>
     * </ol>
     */
    @Scheduled(
            fixedDelayString = "${jooj.cron.processor-tick-ms:200}",
            initialDelayString = "${jooj.cron.processor-initial-delay-ms:500}"
    )
    public void tick() {
        if (!service.hasQueued()) return;
        // 用户输入正在跑 → 不抢,等下一轮
        if (!agentLock.tryLock()) return;
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
            agentLock.unlock();
        }
    }
}
