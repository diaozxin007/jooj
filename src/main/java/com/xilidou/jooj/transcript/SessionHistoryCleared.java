package com.xilidou.jooj.transcript;

import java.time.Instant;
import java.util.UUID;

/**
 * Session 历史清空事件 —— D6 语义扩展。
 *
 * <p>跟 {@link SessionDeleted} 的语义区分:
 * <ul>
 *   <li>{@link SessionDeleted}:session 从 index 移除,session id 不复存在</li>
 *   <li>{@link SessionHistoryCleared}:session 保留(仍在 index 里可继续对话),
 *       只是历史对话被清空</li>
 * </ul>
 *
 * <p>TranscriptService 收到后**软归档** {@code transcripts/<sid>.jsonl} 到
 * {@code transcripts/.deleted/<sid>-<epoch>.jsonl},跟 {@link SessionDeleted} 处理一致
 * (归档保留能恢复);之后再有事件发布会**重新创建**新的 {@code <sid>.jsonl}(append 模式
 * 会自动创建)。
 *
 * <p>SearchService 收到后**标记 deleted_at** 清索引,跟 delete 语义一致 —— 从用户
 * "看不到之前对话"的视角出发,历史索引不该再命中。
 *
 * <p>发布点:{@link com.xilidou.jooj.session.SessionService#clearHistory}。
 */
public record SessionHistoryCleared(
        UUID eventId,
        String sessionId,
        Instant timestamp
) implements TranscriptEvent {
}
