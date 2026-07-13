package com.xilidou.jooj.transcript;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Transcript 事件处理层 —— 4 个 {@link EventListener} 各写一行到磁盘。
 *
 * <h3>去重(D11)</h3>
 *
 * <p>持有 4096-cap LRU {@code seenEvents},入口 gate 阻断重复 eventId。写盘失败时
 * {@code releaseOnFailure} 回退 LRU,允许 caller 再次 publish 重试。
 *
 * <p>Cap 4096 的量级足够:jooj 单用户单进程,一个 session 里连续 4096+ 事件才有可能被
 * LRU 淘汰,现实中不可及。QPS 场景需要调大 cap 或换算法。
 *
 * <h3>边界(D13)</h3>
 *
 * <p>只监听 {@code TranscriptEvent} 的 4 个子类型,任何 Loop 内部 / Subagent / Teammate 的
 * 消息注入都**不该到达**这里(它们根本不该 publish 事件)。参考文档 §4.6 边界清单。
 *
 * <h3>异常策略</h3>
 *
 * <p>Spring 4.2+ 同步 listener 异常会向 publish 端传播,而 publish 是在 loop 主路径上 ——
 * 所以所有 listener 内必须 try/catch,只 warn log 不重抛。transcript 写失败不阻断 loop。
 */
@Slf4j
public class TranscriptService {

    /** LRU cap —— 详见 D11 分析。 */
    static final int DEDUP_CAP = 4096;

    private final TranscriptStore store;

    /**
     * D11 幂等 LRU。LinkedHashMap access-order + removeEldestEntry 实现。
     *
     * <p>{@link Collections#newSetFromMap} 让 Set 复用 Map 的能力;所有访问在
     * {@code synchronized (seenEvents)} 下,避免 access-order 更新的并发问题。
     */
    private final Set<UUID> seenEvents = Collections.newSetFromMap(
            new LinkedHashMap<UUID, Boolean>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > DEDUP_CAP;
                }
            });

    public TranscriptService(TranscriptStore store) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        this.store = store;
    }

    // ── 只读入口 ────────────────────────────────────────────────

    /** 前端 ChatController 用 —— 读整份 transcript 给用户展示。 */
    public List<TranscriptLine> readAll(String sessionId) throws IOException {
        return store.readAll(sessionId);
    }

    // ── 事件 listener(4 个)─────────────────────────────────────

    @EventListener
    public void onUserMessage(UserMessageReceived e) {
        if (!acquireEvent(e.eventId())) {
            log.debug("[Transcript] dedup skip user event {}", e.eventId());
            return;
        }
        try {
            // s22 架构审查(2026-07-13, B1 refactor):cron 触发合并到本 listener,
            // source 前缀 "cron:" 时落成 role="scheduled",前端按 role 分派渲染系统气泡。
            // 其他 source(web / cli / channel:xxx)一律 role="user"。
            String role = e.source() != null && e.source().startsWith("cron:")
                    ? "scheduled"
                    : "user";
            store.append(e.sessionId(),
                    new TranscriptLine(role, e.content(), e.timestamp(), e.source()));
        } catch (Exception ex) {
            releaseOnFailure(e.eventId());
            log.warn("[Transcript] append user message failed sid={}: {}",
                    e.sessionId(), ex.toString());
        }
    }

    /** D8:cron 触发的 final assistant 也走这条,前端不区分入口。 */
    @EventListener
    public void onAssistantResponse(AssistantResponseCompleted e) {
        if (e.content() == null || e.content().isBlank()) return;
        if (!acquireEvent(e.eventId())) {
            log.debug("[Transcript] dedup skip assistant event {}", e.eventId());
            return;
        }
        try {
            store.append(e.sessionId(),
                    new TranscriptLine("assistant", e.content(), e.timestamp(), null));
        } catch (Exception ex) {
            releaseOnFailure(e.eventId());
            log.warn("[Transcript] append assistant message failed sid={}: {}",
                    e.sessionId(), ex.toString());
        }
    }

    /** D6:软归档,不物理删。 */
    @EventListener
    public void onSessionDeleted(SessionDeleted e) {
        if (!acquireEvent(e.eventId())) {
            log.debug("[Transcript] dedup skip deleted event {}", e.eventId());
            return;
        }
        try {
            store.softDelete(e.sessionId(), e.timestamp());
        } catch (Exception ex) {
            releaseOnFailure(e.eventId());
            log.warn("[Transcript] softDelete failed sid={}: {}",
                    e.sessionId(), ex.toString());
        }
    }

    /**
     * SessionHistoryCleared 事件 —— session 保留但清历史。
     * 跟 {@link #onSessionDeleted} 相同处理:softDelete 到 {@code .deleted/} 归档。
     * 之后再有事件发布会重新创建 {@code <sid>.jsonl},实现"清空历史但保留 session"语义。
     */
    @EventListener
    public void onSessionHistoryCleared(SessionHistoryCleared e) {
        if (!acquireEvent(e.eventId())) {
            log.debug("[Transcript] dedup skip cleared event {}", e.eventId());
            return;
        }
        try {
            store.softDelete(e.sessionId(), e.timestamp());
        } catch (Exception ex) {
            releaseOnFailure(e.eventId());
            log.warn("[Transcript] softDelete on clear failed sid={}: {}",
                    e.sessionId(), ex.toString());
        }
    }

    /**
     * s22 D-8:用户主动打断 turn 事件。
     *
     * <p>落一条特殊 role="interrupted" 的 TranscriptLine —— 让前端 mapper 能按 role
     * 派发出"[已中断]"系统气泡。partialContent 是打断前 lead-agent 已 append 的 assistant
     * 文本(可能为空);为空时只落一条空 content 的中断标记,前端渲染"仅中断"气泡。
     */
    @EventListener
    public void onTurnInterrupted(TurnInterrupted e) {
        if (!acquireEvent(e.eventId())) {
            log.debug("[Transcript] dedup skip interrupted event {}", e.eventId());
            return;
        }
        try {
            String content = e.partialContent() == null ? "" : e.partialContent();
            store.append(e.sessionId(),
                    new TranscriptLine("interrupted", content, e.timestamp(), null));
        } catch (Exception ex) {
            releaseOnFailure(e.eventId());
            log.warn("[Transcript] append interrupted event failed sid={}: {}",
                    e.sessionId(), ex.toString());
        }
    }

    // ── D11 幂等 gate ─────────────────────────────────────────

    /** @return true 表示这个 eventId 首次见,可以处理;false 表示是重复事件。 */
    private boolean acquireEvent(UUID eventId) {
        if (eventId == null) return true; // 防御:没 id 就当每次都是新的(不理想但不 block)
        synchronized (seenEvents) {
            return seenEvents.add(eventId);
        }
    }

    /** 写盘失败时回退,允许 caller 再次 publish 重试。 */
    private void releaseOnFailure(UUID eventId) {
        if (eventId == null) return;
        synchronized (seenEvents) {
            seenEvents.remove(eventId);
        }
    }

    // ── 测试用 hooks ────────────────────────────────────────────

    /** 单测用:检查某 eventId 是否已被 gate 记住(接受过)。 */
    int seenEventsSize() {
        synchronized (seenEvents) {
            return seenEvents.size();
        }
    }
}
