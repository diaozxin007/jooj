package com.xilidou.marvis.cron;

import com.xilidou.marvis.agent.AgentLoopHarness;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cron 4 层架构 — Layer 3:queue processor daemon thread。
 *
 * <p>每 {@link CronConfig#processorTickMs} 毫秒(默认 200ms)轮询 queue,
 * 一旦有 fired job 且能拿到 {@code agentLock},就触发一轮 agent_loop。
 *
 * <p>对应上游 s14 {@code _process_cron_queue} 的 thread 形态。
 *
 * <h3>循环依赖打破:{@code @Lazy}</h3>
 *
 * <p>本类需要 {@link AgentLoopHarness},但 {@link AgentLoopHarness#agentLoop}
 * 本身要 {@code drainQueue},如果直接构造注入会成环:
 * {@code CronQueueProcessor → AgentLoopHarness → ToolRegistry → CronTool → CronService}
 * (CronService 是 CronTool 持有的)。
 *
 * <p>解法 <b>跟 {@link com.xilidou.marvis.tool.impl.TaskTool} 一样用 {@code @Lazy}</b>:
 * Spring 给本类注入一个 {@link AgentLoopHarness} 的代理,首次调用方法时才解析真实
 * Bean。处理器 thread 的 loop 用得很慢(只在 fire 时才调 harness),代理开销忽略不计。
 *
 * <h3>agentLock 设计</h3>
 *
 * <p>{@link ReentrantLock} 是个 Spring {@code @Bean} 单例。两个地方拿这个锁:
 * <ul>
 *   <li>REPL 用户输入流程(在 {@link AgentLoopHarness#repl})</li>
 *   <li>本类 cron 触发流程(在 {@link #loop})</li>
 * </ul>
 *
 * <p>用户正在跟 marvis 对话时,cron 触发要等用户那一轮结束(用 {@link ReentrantLock#tryLock}
 * 不阻塞)。这样避免 cron-fired turn 跟 user-input turn 抢 messages list 撞车。
 */
@Component
@Slf4j
public class CronQueueProcessor {

    private final CronService service;
    private final AgentLoopHarness harness;
    private final ReentrantLock agentLock;
    private final long tickMs;

    private volatile boolean running = false;
    private Thread thread;

    public CronQueueProcessor(CronService service,
                              @Lazy AgentLoopHarness harness,
                              @Qualifier("agentLock") ReentrantLock agentLock,
                              CronConfig config) {
        this.service = service;
        this.harness = harness;
        this.agentLock = agentLock;
        this.tickMs = config.processorTickMs();
    }

    @PostConstruct
    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "marvis-cron-processor");
        thread.setDaemon(true);
        thread.start();
        log.info("[Cron] queue processor thread started (tick={}ms)", tickMs);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(tickMs);
                if (!service.hasQueued()) continue;
                // 用户输入正在跑 → 不抢,等下一轮
                if (!agentLock.tryLock()) continue;
                try {
                    // 拿到锁后再 double-check,避免别的线程刚 drain 走
                    if (!service.hasQueued()) continue;
                    List<CronJob> fired = service.drainQueue();
                    if (fired.isEmpty()) continue;
                    log.info("[Cron] processing {} fired job(s)", fired.size());
                    harness.processCronTriggers(fired);
                } finally {
                    agentLock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[Cron] processor tick failed: {}", e.toString());
            }
        }
    }

    /** 测试用。 */
    public boolean isRunning() {
        return running && thread != null && thread.isAlive();
    }
}
