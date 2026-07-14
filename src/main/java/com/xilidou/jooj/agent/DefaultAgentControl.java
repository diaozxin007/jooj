package com.xilidou.jooj.agent;

import com.xilidou.jooj.agent.control.Answer;
import com.xilidou.jooj.agent.control.AskTimeoutException;
import com.xilidou.jooj.agent.control.DenyAnswer;
import com.xilidou.jooj.agent.control.PendingQuestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * s22 D-10:{@link AgentControl} 默认实现 —— 单进程 JVM + ConcurrentHashMap。
 *
 * <p><b>历史</b>:D-8 最初叫 {@code InterruptRegistry},只有 signal 部分。D-10-A 把它改名 +
 * 上到接口后,D-10-B 加了 ask 部分(pending question / CompletableFuture 唤醒)。
 *
 * <p><b>为什么单 Bean 就够</b>:jooj 是单进程 JVM(pidfile guard),不涉及跨进程可见性;
 * REST 线程 write flag,agentLoop 线程 read/consume,ConcurrentHashMap 足够。
 * 未来若需要多进程,该抽象换 Redis / ZK 实现即可,接口不变。
 *
 * <h3>Ask 内部数据结构</h3>
 *
 * <p>{@code sessionAsks: Map<sid, Map<askId, PendingAsk>>} —— 双层 map:
 * <ul>
 *   <li>外层按 sid 隔离(cancelPending / listPending 按 sid 遍历)</li>
 *   <li>内层按 askId 查(answer / findPending 精确 lookup)</li>
 * </ul>
 *
 * <p>{@code PendingAsk} 是内部 record,把 question 和 CompletableFuture 绑一起。
 * agent 线程 await future,REST 线程 complete future,ConcurrentHashMap
 * 提供 put/remove/get 原子性。
 */
@Component
@Slf4j
public class DefaultAgentControl implements AgentControl {

    /** Set 语义,ConcurrentHashMap.newKeySet 拿并发 add/remove. */
    private final Set<String> pendingInterrupts = ConcurrentHashMap.newKeySet();

    /**
     * 双层 map:sid → (askId → PendingAsk)。外层 ConcurrentHashMap.computeIfAbsent 保证
     * 首次挂起时并发安全的懒建 inner map。
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, PendingAsk>> sessionAsks
            = new ConcurrentHashMap<>();

    /** 内部记录:question + 完成信号 future. */
    private record PendingAsk(PendingQuestion question, CompletableFuture<Answer> future) {}

    /**
     * s22 SSE:pending 入队时发 Spring event,让 web 层 SseStreamService 立即 push 到浏览器。
     * ObjectProvider 让测试路径(直接 new DefaultAgentControl())保持能用。
     */
    private final ObjectProvider<ApplicationEventPublisher> publisherProvider;

    /** Spring 容器构造。 */
    public DefaultAgentControl(ObjectProvider<ApplicationEventPublisher> publisherProvider) {
        this.publisherProvider = publisherProvider;
    }

    /** 测试路径 —— 无 event publisher。 */
    public DefaultAgentControl() {
        this.publisherProvider = null;
    }

    // ── signal 部分 (D-10-A) ─────────────────────────────────

    @Override
    public boolean requestInterrupt(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        boolean added = pendingInterrupts.add(sessionId);
        if (added) {
            log.info("[AgentControl] interrupt requested sid={}", sessionId);
            // D-10-B step 4:interrupt 到达时,同时 cancel 所有 pending ask,
            // 让挂起的 agent 线程立即抛 AgentInterruptedException,而不用等 timeout
            int cancelled = cancelPending(sessionId);
            if (cancelled > 0) {
                log.info("[AgentControl] cancelled {} pending ask(s) on interrupt sid={}",
                        cancelled, sessionId);
            }
        } else {
            log.debug("[AgentControl] duplicate interrupt request sid={} (already pending)", sessionId);
        }
        return added;
    }

    @Override
    public boolean consumeInterrupt(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        boolean removed = pendingInterrupts.remove(sessionId);
        if (removed) {
            log.info("[AgentControl] interrupt consumed sid={} — loop will stop", sessionId);
        }
        return removed;
    }

    @Override
    public boolean isInterruptRequested(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return pendingInterrupts.contains(sessionId);
    }

    @Override
    public void clearInterrupt(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        pendingInterrupts.remove(sessionId);
    }

    /** 测试可见:当前挂起 interrupt 请求数。 */
    int pendingInterruptCount() {
        return pendingInterrupts.size();
    }

    // ── ask 部分 (D-10-B) ────────────────────────────────────

    @Override
    public Answer ask(String sessionId, PendingQuestion question, Duration timeout)
            throws AskTimeoutException, AgentInterruptedException, InterruptedException {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId required");
        }
        if (question == null) {
            throw new IllegalArgumentException("question required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got: " + timeout);
        }

        // 边界:如果这个 sid 已经在 interrupt 状态,直接抛 —— 别挂起
        if (isInterruptRequested(sessionId)) {
            log.info("[AgentControl] ask fast-fail: sid={} already interrupted", sessionId);
            throw new AgentInterruptedException(sessionId);
        }

        CompletableFuture<Answer> future = new CompletableFuture<>();
        PendingAsk pending = new PendingAsk(question, future);

        ConcurrentHashMap<String, PendingAsk> inner = sessionAsks
                .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        inner.put(question.askId(), pending);

        log.info("[AgentControl] ask queued sid={} askId={} type={} timeout={}s",
                sessionId, question.askId(), question.type(), timeout.toSeconds());

        // s22 SSE:发 Spring event,SseStreamService 监听转 SSE push
        if (publisherProvider != null) {
            ApplicationEventPublisher pub = publisherProvider.getIfAvailable();
            if (pub != null) {
                pub.publishEvent(new PendingQuestionRegistered(sessionId, question));
            }
        }

        try {
            // 阻塞等 answer / timeout / cancel(cancel 走 future.completeExceptionally)
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.info("[AgentControl] ask timed out sid={} askId={}", sessionId, question.askId());
            throw new AskTimeoutException(question.askId());
        } catch (ExecutionException ee) {
            // cancelPending 走 future.completeExceptionally(new AgentInterruptedException(...))
            Throwable cause = ee.getCause();
            if (cause instanceof AgentInterruptedException aie) throw aie;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("Ask execution failed", cause);
        } finally {
            // 无论怎么退出,清 pending —— answer 已经拿到就不再需要占位
            inner.remove(question.askId());
            // inner 空了顺手清 outer,避免 sid 累积
            if (inner.isEmpty()) {
                sessionAsks.remove(sessionId, inner);
            }
        }
    }

    @Override
    public List<PendingQuestion> listPending(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        ConcurrentHashMap<String, PendingAsk> inner = sessionAsks.get(sessionId);
        if (inner == null || inner.isEmpty()) return List.of();
        // 拷贝一份,防止 caller 遍历时并发修改
        List<PendingQuestion> out = new ArrayList<>(inner.size());
        for (PendingAsk pa : inner.values()) out.add(pa.question());
        return out;
    }

    @Override
    public boolean answer(String sessionId, String askId, Answer answer) {
        if (sessionId == null || sessionId.isBlank() || askId == null || answer == null) return false;
        ConcurrentHashMap<String, PendingAsk> inner = sessionAsks.get(sessionId);
        if (inner == null) return false;
        PendingAsk pending = inner.get(askId);
        if (pending == null) return false;
        // future.complete 是 no-op 如果已经完成(timeout / cancel),这里返 false 让 REST 层给 409
        boolean ok = pending.future().complete(answer);
        if (ok) {
            log.info("[AgentControl] ask answered sid={} askId={} answer={}",
                    sessionId, askId, answer.getClass().getSimpleName());
        } else {
            log.warn("[AgentControl] ask already completed sid={} askId={} — answer ignored",
                    sessionId, askId);
        }
        return ok;
    }

    @Override
    public Optional<PendingQuestion> findPending(String sessionId, String askId) {
        if (sessionId == null || askId == null) return Optional.empty();
        ConcurrentHashMap<String, PendingAsk> inner = sessionAsks.get(sessionId);
        if (inner == null) return Optional.empty();
        PendingAsk pa = inner.get(askId);
        return pa != null ? Optional.of(pa.question()) : Optional.empty();
    }

    @Override
    public int cancelPending(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return 0;
        ConcurrentHashMap<String, PendingAsk> inner = sessionAsks.get(sessionId);
        if (inner == null || inner.isEmpty()) return 0;
        // 快照防止遍历时被修改
        Collection<PendingAsk> snapshot = new ArrayList<>(inner.values());
        int count = 0;
        for (PendingAsk pa : snapshot) {
            boolean ok = pa.future().completeExceptionally(new AgentInterruptedException(sessionId));
            if (ok) count++;
        }
        return count;
    }

    /** 测试可见:sid 当前 pending 数量。 */
    int pendingAskCount(String sessionId) {
        ConcurrentHashMap<String, PendingAsk> inner = sessionAsks.get(sessionId);
        return inner != null ? inner.size() : 0;
    }
}
