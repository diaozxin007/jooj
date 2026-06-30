package com.xilidou.jooj.memory;

/**
 * Memory 总容量超限抛出 —— s21 Demo 21,对齐 Hermes ~1300 token 配额哲学。
 *
 * <p>语义:**让 LLM 主动 GC**,而不是 jooj 静默截断 / 静默丢失。
 *
 * <p>触发点:{@link MemoryStore#write} 写入会让 {@code totalBodyChars()} 超过
 * {@link MemoryConfig#totalMaxBytes()} 时抛出。
 *
 * <p>{@link MemoryService} 把异常包装成 LLM 可读的 error 返回(memory tool result),
 * LLM 看到 used/limit 数字后会主动调 replace 老 entry 或 remove 不再相关的 entry,
 * 而不是无脑 add 把 SYSTEM 撑爆。
 *
 * <p>跟 {@link MemoryConfig#maxBodyBytes()} 的区分:
 * <ul>
 *   <li>{@code maxBodyBytes} 是<b>单条</b>上限,write 时静默截断 + 加 "..."(老行为,Demo 21 不动)</li>
 *   <li>{@code totalMaxBytes} 是<b>总量</b>上限,write 时显式抛(本异常,Demo 21 新加)</li>
 * </ul>
 */
public class MemoryQuotaExceededException extends RuntimeException {

    private final int currentBytes;
    private final int incomingBytes;
    private final int limitBytes;

    public MemoryQuotaExceededException(int currentBytes, int incomingBytes, int limitBytes) {
        super(String.format(
                "Memory quota exceeded: current=%d + incoming=%d would exceed limit=%d. "
                        + "Use memory_replace or memory_delete to free space before adding.",
                currentBytes, incomingBytes, limitBytes));
        this.currentBytes = currentBytes;
        this.incomingBytes = incomingBytes;
        this.limitBytes = limitBytes;
    }

    public int currentBytes() {
        return currentBytes;
    }

    public int incomingBytes() {
        return incomingBytes;
    }

    public int limitBytes() {
        return limitBytes;
    }
}
