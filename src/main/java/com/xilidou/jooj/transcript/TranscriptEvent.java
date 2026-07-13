package com.xilidou.jooj.transcript;

import java.time.Instant;
import java.util.UUID;

/**
 * Transcript 域的事件基类 —— 用户可见对话记录的所有 signals。
 *
 * <h3>为什么是独立事件而非 MessageParam 的一部分</h3>
 *
 * <p>参考 [[s22_改造规划_事件驱动Transcript]] §六:jooj 里 {@code List<MessageParam> history}
 * 一份数据同时担着 LLM 上下文 / transcript / 搜索索引 / 前端展示 4 个职责,
 * memory prefetch 之类的注入必然污染其中一部分 consumer。
 *
 * <p>方案:把"用户可见对话"抬到独立 domain,靠事件流分叉。
 * loop 里往 {@code history} 加什么都不影响 transcript,transcript 只接收显式发布的事件。
 *
 * <h3>4 个具体事件类型</h3>
 *
 * <ul>
 *   <li>{@link UserMessageReceived} —— 用户在 Web/CLI/Channel 发起的对话;
 *       cron 触发也走这条,靠 {@code source} 前缀 {@code "cron:jobId"} 区分</li>
 *   <li>{@link AssistantResponseCompleted} —— lead-agent 最终回复</li>
 *   <li>{@link SessionDeleted} —— session 从 index 移除,transcript 软归档</li>
 *   <li>{@link SessionHistoryCleared} —— session 保留但清历史,transcript 同样软归档
 *       (语义区分见事件类注释)</li>
 *   <li>{@link TurnInterrupted} —— s22 D-8:用户主动打断当前 turn,前端渲染中断气泡</li>
 * </ul>
 *
 * <p><b>s22 架构审查(2026-07-13, B1 refactor)</b>:删除 {@code ScheduledPromptFired}
 * 独立事件类型。Hermes 参考实现证明"单一 turn 入口 + 单一入口事件"更清晰。cron 走
 * {@code UserMessageReceived + source="cron:jobId"},前端 mapper 按 source 前缀渲染
 * 系统气泡(而非 role="scheduled")。原来的 D7 决策(独立事件)撤回。
 *
 * <h3>边界(D13)</h3>
 *
 * <p>只记 user(含 scheduled)↔ <b>lead-agent</b> 的对话:
 * <ul>
 *   <li>Subagent / Teammate 之间的内部通信 —— 不发事件</li>
 *   <li>Loop 内部纠错消息(nag / continuation / verification / drainLeadInbox) —— 不发事件</li>
 * </ul>
 *
 * <h3>幂等(D11)</h3>
 *
 * <p>每个事件必须带 {@link #eventId()},发布方 {@code UUID.randomUUID()} 生成。
 * TranscriptService / SearchService 各自维护 4096-cap LRU 去重 —— 调用方重试时同一 eventId
 * 只落一次。写盘失败时 LRU 会回退允许重试。
 */
public sealed interface TranscriptEvent
        permits UserMessageReceived,
                AssistantResponseCompleted, SessionDeleted,
                SessionHistoryCleared, TurnInterrupted {

    /** D11 幂等锚点。发布方 UUID.randomUUID() 生成,listener 用于去重。 */
    UUID eventId();

    /** 事件所属 session。 */
    String sessionId();

    /** 事件发生时间。 */
    Instant timestamp();
}
