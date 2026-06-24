package com.xilidou.marvis.agent;

/**
 * 单次 {@code agentLoop} 调用内的恢复状态机。每次进 agentLoop 都 new 一个,
 * 跨调用不污染。
 *
 * <p>5 个字段对应 plan 里的三条恢复路径:
 * <ul>
 *   <li>{@link #hasEscalated} / {@link #recoveryCount} / {@link #currentMaxTokens}
 *       —— Path 1(max_tokens 截断)</li>
 *   <li>{@link #hasAttemptedReactiveCompact} —— Path 2(prompt_too_long),限一次</li>
 *   <li>{@link #consecutive529} / {@link #currentModel} —— Path 3(529 切 fallback)</li>
 * </ul>
 *
 * <p>POJO 而非 record:字段需要在 retry 过程中**就地变更**(consecutive529 累加、
 * hasEscalated 置 true、currentModel 切到 fallback),record 的 immutability 反而碍事。
 */
public class RecoveryState {

    // ── Path 1: max_tokens 升级 ────────────────────────────────
    /** 是否已经从 defaultMaxTokens 升级到 escalatedMaxTokens 过一次。 */
    boolean hasEscalated = false;

    /** 已经追加过多少次 continuation prompt(超过 maxRecoveryRetries 就放弃)。 */
    int recoveryCount = 0;

    /** 当前请求用的 max_tokens。Path 1 第一次截断时改成 escalatedMaxTokens。 */
    int currentMaxTokens;

    // ── Path 2: prompt_too_long ────────────────────────────────
    /** 是否已经做过一次 reactive compact。语义上限一次,失败直接抛。 */
    boolean hasAttemptedReactiveCompact = false;

    // ── Path 3: 429/529 ────────────────────────────────────────
    /** 累计连续 529 次数。一次成功调用清零。 */
    int consecutive529 = 0;

    /** 当前请求用的 model id。Path 3 触发 fallback 时改成 fallbackModel。 */
    String currentModel;

    /**
     * 构造器:用初始 model 和 max_tokens 启动。这两个值是请求构建的"出发点",
     * 在 retry 中可被 mutate(currentMaxTokens 升级、currentModel 切 fallback)。
     */
    public RecoveryState(String initialModel, int initialMaxTokens) {
        this.currentModel = initialModel;
        this.currentMaxTokens = initialMaxTokens;
    }

    // ── 只读 getter,给测试和日志用 ─────────────────────────────
    public boolean isHasEscalated() { return hasEscalated; }
    public int getRecoveryCount() { return recoveryCount; }
    public int getCurrentMaxTokens() { return currentMaxTokens; }
    public boolean isHasAttemptedReactiveCompact() { return hasAttemptedReactiveCompact; }
    public int getConsecutive529() { return consecutive529; }
    public String getCurrentModel() { return currentModel; }
}
