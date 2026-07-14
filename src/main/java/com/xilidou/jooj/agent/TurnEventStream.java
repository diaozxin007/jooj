package com.xilidou.jooj.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * s22 D-11:per-session ring buffer,存 agent turn 期间产生的 {@link TurnEvent},
 * 给前端 poll {@code /api/chat/{sid}/events} 拿实时进度。
 *
 * <h3>为什么 ring buffer 而不是无限队列</h3>
 *
 * <p>如果 LLM 一轮跑 200 个 tool(极端场景),没上限的话内存会涨。ring buffer 大小
 * {@link #MAX_PER_SESSION} 兜底 —— 老事件被丢弃,新事件覆盖。前端只需要看
 * "最近发生了什么",老事件被丢是可接受的(前端 poll 到就渲染,渲染完就滚出气泡)。
 *
 * <h3>并发语义</h3>
 *
 * <p>Agent 线程 {@link #push} 写,REST 线程 {@link #since} 读;两个操作用 inner 集合的
 * {@code synchronized(deque)} 隔离,ConcurrentHashMap 提供 outer 层线程安全。
 *
 * <p>不用 CopyOnWriteArrayList —— push 频率可能高(每次 tool_use 一次),CoW 每次全拷贝
 * 太贵;不用 LinkedBlockingQueue —— 我们不需要 blocking 读,只要 since 快照返回。
 *
 * <h3>与 AgentControl 的区分</h3>
 *
 * <ul>
 *   <li>{@link AgentControl} —— **控制平面**(interrupt / approval),ask 阻塞</li>
 *   <li>{@link TurnEventStream} —— **观察平面**(状态汇报),纯 push 单向</li>
 * </ul>
 *
 * <p>俩合并会把职责搅乱 —— 控制平面强 typed 结构 + 阻塞语义,观察平面松 typed 字符串 + 非阻塞。
 */
@Component
@Slf4j
public class TurnEventStream {

    /**
     * 单 session 最多保留的事件数。ring buffer 溢出后老事件被弹出。
     *
     * <p>200 的选型:LLM 一轮极端场景 60 个 tool_use,预留 3x 空间,前端 poll 频率
     * 1s 内一定拿得完,不会丢事件;真出现 200+ tool 的 turn(rare),前端只看到最近 200 个,
     * 老的进 log。
     */
    public static final int MAX_PER_SESSION = 200;

    /** sid → 事件序列 (ArrayDeque 起 ring buffer)。 */
    private final ConcurrentHashMap<String, SessionEvents> sessions = new ConcurrentHashMap<>();

    /**
     * s22 SSE:push 时同时发 Spring event,让 web 层 SseStreamService 监听转 SSE。
     * ObjectProvider 让老的**无参构造器**测试路径保持能用(不装 publisher)。
     */
    private final ObjectProvider<ApplicationEventPublisher> publisherProvider;

    /** Spring 容器构造。 */
    public TurnEventStream(ObjectProvider<ApplicationEventPublisher> publisherProvider) {
        this.publisherProvider = publisherProvider;
    }

    /** 测试路径无 event publisher —— 现有 TurnEventStreamTest 全部走这条不改。 */
    public TurnEventStream() {
        this.publisherProvider = null;
    }

    /** Per-session 计数器 + deque 打包,避免 outer map 分开维护。 */
    private static class SessionEvents {
        final AtomicLong nextSeq = new AtomicLong(1L);
        final Deque<TurnEvent> deque = new ArrayDeque<>(MAX_PER_SESSION);
    }

    /**
     * 推一条事件到指定 session 的 stream 尾部。分配 seq,溢出时弹出最老。
     *
     * <p>调用方:agent loop(lead / subagent / teammate)在 tool 循环里 tool_use 之前调:
     * <pre>
     *   String s = tool.summary(call);
     *   turnEventStream.push(sid, TurnEvent.toolStart(s));
     *   registry.execute(...);
     * </pre>
     *
     * <p>sid null / blank / event null → 静默跳过(agent 内部很多路径 sid 可能未绑,不该崩)。
     */
    public void push(String sessionId, TurnEvent event) {
        if (sessionId == null || sessionId.isBlank() || event == null) return;
        SessionEvents se = sessions.computeIfAbsent(sessionId, k -> new SessionEvents());
        long seq;
        // 整个 seq 分配 + offer 必须在同一 sync 块 —— 否则两个线程 race 到不同 seq 后
        // offer 的顺序可能颠倒(先拿 seq 的线程晚 offer),违反 deque 里 seq 单调递增契约
        synchronized (se.deque) {
            seq = se.nextSeq.getAndIncrement();
            TurnEvent stamped = new TurnEvent(seq, event.at(), event.type(), event.summary());
            if (se.deque.size() >= MAX_PER_SESSION) {
                se.deque.pollFirst();  // 丢最老
            }
            se.deque.offerLast(stamped);
        }
        log.debug("[TurnEventStream] push sid={} seq={} type={} summary={}",
                sessionId, seq, event.type(), event.summary());

        // s22 SSE:发 Spring event,SseStreamService 监听转 SSE push。测试路径 publisher=null 时跳过。
        if (publisherProvider != null) {
            ApplicationEventPublisher pub = publisherProvider.getIfAvailable();
            if (pub != null) {
                TurnEvent stampedCopy;
                // 从 deque 找回带 seq 的版本(offerLast 已放进去,再取一遍避免用旧引用)
                synchronized (se.deque) {
                    stampedCopy = se.deque.peekLast();
                }
                if (stampedCopy != null) {
                    pub.publishEvent(new TurnEventPushed(sessionId, stampedCopy));
                }
            }
        }
    }

    /**
     * 返回 seq > {@code since} 的所有事件,按 seq 升序。前端 poll 首次传 {@code since=0}
     * 拿全部,后续传上次的 max seq 拿增量。
     *
     * <p>返回**快照**(拷贝),caller 遍历时不受并发 push 影响。
     */
    public List<TurnEvent> since(String sessionId, long since) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        SessionEvents se = sessions.get(sessionId);
        if (se == null) return List.of();
        List<TurnEvent> out = new ArrayList<>();
        synchronized (se.deque) {
            for (TurnEvent e : se.deque) {
                if (e.seq() > since) out.add(e);
            }
        }
        return out;
    }

    /**
     * 当前 sid 已分配的最大 seq(no events → 0)。前端可选:先 GET latest 拿基线,
     * 再增量 poll。目前实现不需要暴露给前端,{@link #since} 已包含最新 seq。
     */
    public long latestSeq(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return 0L;
        SessionEvents se = sessions.get(sessionId);
        if (se == null) return 0L;
        return se.nextSeq.get() - 1;
    }

    /**
     * 清空 sid 下所有事件。turn 结束(processOneQuery 返回)时清理,防止累积。
     * 也在 session delete 时调用。
     */
    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        sessions.remove(sessionId);
    }

    /** 测试用:sid 当前事件条数。 */
    int size(String sessionId) {
        SessionEvents se = sessions.get(sessionId);
        if (se == null) return 0;
        synchronized (se.deque) {
            return se.deque.size();
        }
    }
}
