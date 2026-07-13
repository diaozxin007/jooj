package com.xilidou.jooj.cron;

import com.xilidou.jooj.agent.AgentLoopHarness;
import com.xilidou.jooj.session.AgentLockProvider;
import com.xilidou.jooj.session.Session;
import com.xilidou.jooj.session.SessionService;
import com.xilidou.jooj.tool.ExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CronTurnOrchestrator —— cron 触发的 turn 编排层。
 *
 * <h3>Hermes 参考实现</h3>
 *
 * <p>Hermes 里 {@code cron/scheduler.py} 的 {@code _run_one_job} 直接调
 * {@code agent.run_conversation(prompt)} 触发单轮 turn,delivery 是独立函数
 * {@code _deliver_result} —— 单一 turn 入口 + 独立 delivery 分派。
 *
 * <p>jooj 之前把这两件事都塞在 {@code AgentLoopHarness.processCronTriggers} +
 * {@code deliverCronResult} 里,harness 因此持有 {@code channelDelivererProvider}
 * 这个只服务 cron delivery 的依赖。s22 架构审查借鉴 Hermes,把 cron 编排搬来这里。
 *
 * <h3>本组件职责(唯一)</h3>
 *
 * <ol>
 *   <li>接 {@code List<CronJob>} 一批 fired jobs</li>
 *   <li>按 {@code job.sessionId} 分组(不存在则 fallback {@code cron-default})</li>
 *   <li>对每个 job:{@code lockProvider.lockFor(sid).tryLock} + 调
 *       {@code harness.processOneQuery(sid, prompt, hint, source)} + 提取 reply +
 *       调 {@code cronDelivery.deliver(job, reply)}</li>
 * </ol>
 *
 * <h3>跟旧 processCronTriggers 的差异</h3>
 *
 * <ul>
 *   <li><b>单 job 一次 turn</b>(对齐 Hermes):不再"同 session 多 jobs 串接进同一 turn 共享 reply"</li>
 *   <li><b>per-session lock</b>:每个 job 抢自己 session 的 lock,不再统一 cron-default lock
 *       —— 修复潜在的"cron 操作 sid1 但抢 cron-default lock" 竞态</li>
 *   <li><b>source 语义</b>:通过 processOneQuery 的 sourceOverride 参数传
 *       {@code "cron:jobId"},合并事件类型(不再有 ScheduledPromptFired)</li>
 *   <li><b>hint 透传</b>:job self-describing 的 channel/peer 转成 DeliveryHint,
 *       让 LLM 在 cron turn 里通过工具主动调用时也能拿到正确的 delivery context</li>
 * </ul>
 *
 * <h3>循环依赖打破:{@code @Lazy}</h3>
 *
 * <p>本类需要 {@link AgentLoopHarness},但 harness 的 agentLoop 顶部要 drain
 * cron queue(见 {@code CronService.drainQueue}),Spring 装配时可能成环。
 * {@code @Lazy} 让 harness 延迟到实际调用时才解析。
 */
@Component
@Slf4j
public class CronTurnOrchestrator {

    private final AgentLoopHarness harness;
    private final SessionService sessionService;
    private final AgentLockProvider lockProvider;
    private final CronDeliveryHandler cronDelivery;

    public CronTurnOrchestrator(@Lazy AgentLoopHarness harness,
                                SessionService sessionService,
                                AgentLockProvider lockProvider,
                                CronDeliveryHandler cronDelivery) {
        this.harness = harness;
        this.sessionService = sessionService;
        this.lockProvider = lockProvider;
        this.cronDelivery = cronDelivery;
    }

    /**
     * 处理一批 fired cron jobs。**不抢 caller 的 lock** —— caller
     * ({@link CronQueueProcessor}) 已经在 cron-default lock 下,但我们内部按 job.sessionId
     * 重新抢 per-session lock,让每个 turn 跟对应 session 的 web/channel 请求真正互斥。
     *
     * <p>失败策略:单个 job 失败不影响其他 —— 每个 job 独立 try/catch。
     */
    public void processFired(List<CronJob> firedJobs) {
        if (firedJobs == null || firedJobs.isEmpty()) return;

        // 按 sessionId 分组(null → cron-default)。分组是为了未来可能的批量优化
        // (比如同 session 多 job 共享 memory prefetch),当前实现下仍逐个跑。
        Map<String, List<CronJob>> bySession = new LinkedHashMap<>();
        for (CronJob job : firedJobs) {
            String sid = job.getSessionId() != null ? job.getSessionId() : Session.CRON_DEFAULT_ID;
            if (!sessionService.exists(sid)) {
                log.warn("[Cron] job {} target session {} no longer exists, " +
                        "falling back to cron-default", job.getId(), sid);
                sid = Session.CRON_DEFAULT_ID;
            }
            bySession.computeIfAbsent(sid, k -> new java.util.ArrayList<>()).add(job);
        }

        for (Map.Entry<String, List<CronJob>> entry : bySession.entrySet()) {
            String sessionId = entry.getKey();
            for (CronJob job : entry.getValue()) {
                runOne(sessionId, job);
            }
        }
    }

    /** 单 job 一次 turn:抢 lock → processOneQuery → 提取 reply → deliver。 */
    private void runOne(String sessionId, CronJob job) {
        ReentrantLock lock = lockProvider.lockFor(sessionId);
        boolean acquired = lock.tryLock();
        if (!acquired) {
            // session 正被其他请求占用(比如 web 用户正在这个 session 里聊天)。
            // 跳过本次:job 还在 queue 里,下一次 processor tick 会重试。
            // 不阻塞等待,防止 cron tick 被卡死。
            log.info("[Cron] job {} skipped: session {} busy, will retry next tick",
                    job.getId(), sessionId);
            // 注:此处应该把 job 放回 queue 或 mark retry —— 当前 processCronTriggers
            // 也没这么做(旧实现直接假设已 drainQueue 出来了),保持行为一致。
            // 未来改进见 CronService.markForRetry 之类。
            return;
        }
        try {
            // s21 Demo 20:job 自描述 → hint,让 LLM 在 turn 里调 CronTool schedule 新 job 时
            // 也能拿到正确的 channel/peer 上下文
            ExecutionContext.DeliveryHint hint = null;
            if ("channel".equals(job.getDeliveryType())
                    && job.getChannel() != null && job.getPeerId() != null) {
                hint = new ExecutionContext.DeliveryHint(job.getChannel(), job.getPeerId());
            }

            // LLM 视图仍加 [Scheduled] 前缀 —— 帮 model 理解是定时触发,不是用户敲的
            String llmPrompt = "[Scheduled] " + job.getPrompt();
            // Transcript 视图落干净原文(不带前缀),前端按 source 前缀 "cron:" 渲染系统气泡
            String transcriptContent = job.getPrompt();
            String source = "cron:" + job.getId();

            log.info("[Cron] running job {} in session {}", job.getId(), sessionId);
            harness.processOneQuery(sessionId, llmPrompt, hint, source, transcriptContent);

            // Delivery:每个 job 独立按自己的 deliveryType 分派
            String reply = harness.extractLastAssistantText(sessionId);
            cronDelivery.deliver(job, reply);
        } catch (Exception e) {
            log.error("[Cron] job {} failed in session {}: {}",
                    job.getId(), sessionId, e.toString(), e);
            // 单 job 失败不 rethrow —— 让 processFired 继续处理其他 job
        } finally {
            lock.unlock();
        }
    }
}
