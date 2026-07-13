package com.xilidou.jooj.transcript;

import java.time.Instant;
import java.util.UUID;

/**
 * Session 删除事件 —— D6 软归档语义。
 *
 * <p>发布点:{@code SessionService.delete(sessionId)} 里,在删除主 session JSON 后发出。
 *
 * <p>TranscriptService 收到后**软归档**:把 {@code transcripts/<sid>.jsonl} 移动到
 * {@code transcripts/.deleted/<sid>-<epoch>.jsonl},保留恢复能力(用户误删的救生梯)。
 *
 * <p>SearchService 收到后**标记 deleted_at**:不物理删索引行,查询默认过滤,
 * 未来若需要审计 {@code --include-deleted} 可再启用。
 *
 * <p>{@code timestamp} 用作归档文件名后缀,同一 sid 反复创建+删除时防冲突。
 */
public record SessionDeleted(
        UUID eventId,
        String sessionId,
        Instant timestamp
) implements TranscriptEvent {
}
