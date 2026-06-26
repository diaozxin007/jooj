package com.xilidou.jooj.compact;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Compact 配置常量集中。
 *
 * <p>所有阈值默认值都在这里,测试可通过全参构造器覆盖默认值(低阈值更易触发),
 * 生产用无参构造器走默认值。
 *
 * <p>设计理由:
 * <ul>
 *   <li>不复用 {@code @ConfigurationProperties}:当前 jooj 主线代码不依赖 Spring
 *       容器({@link com.xilidou.jooj.agent.AgentLoopHarness#fromEnv()}
 *       手工装配所有依赖),保持纯 POJO 与现有风格一致</li>
 *   <li>不写 Lombok {@code @Data}:getter 改成无参方法名风格({@code maxMessages()}),
 *       与 plan 草案签名对齐,外部不可变</li>
 *   <li>未来切到 Spring 容器时,加 {@code @Component} +
 *       {@code @ConfigurationProperties("jooj.compact")} 不破坏现有调用</li>
 * </ul>
 *
 * <p>L1/L2 常量含义:
 * <ul>
 *   <li>{@code maxMessages=50}:messages 总条数 ≤ 50 时 L1 不动;
 *       > 50 时进入裁剪</li>
 *   <li>{@code snipHeadKeep=3}:L1 裁剪时头部保留的消息数(含原始 user query)</li>
 *   <li>{@code keepRecent=3}:L2 micro 占位时,最后 N 个 tool_result 保留原文</li>
 *   <li>{@code minPlaceholderLen=120}:L2 替换的下限——内容短于这个值不替换
 *       (占位符本身约 60 字符,微小输出不浪费 token)</li>
 * </ul>
 *
 * <p>L3 常量含义:
 * <ul>
 *   <li>{@code maxToolResultBytes=10000}:单条 tool_result content 字符数 > 这个值
 *       才落盘(教学版用字符数估算 token)。10000 字符 ≈ 2500 token,
 *       一两条就能挤掉一半 context</li>
 *   <li>{@code taskOutputDir=".task_outputs/tool-results"}:相对 cwd 的落盘目录,
 *       与 RTK 的 {@code .task_outputs/} 风格保持一致</li>
 * </ul>
 *
 * <p>L4 常量含义:
 * <ul>
 *   <li>{@code summaryHeadKeep=3}:L4 摘要时头部保留的消息数(原始 user query)</li>
 *   <li>{@code summaryTailKeep=10}:L4 摘要时尾部保留的消息数(最近上下文)
 *       —— 比 L1 的 snipHeadKeep 大,因为 L4 触发时已经爆 context,
 *       需要尽量保留近因让模型继续推理</li>
 *   <li>{@code transcriptDir=".transcripts"}:摘要前原始中段消息的存档目录</li>
 *   <li>{@code summaryMaxChars=500}:摘要文本上限,约 125 token,
 *       够描述任务状态,又不至于喧宾夺主</li>
 * </ul>
 */
public class CompactConfig {

    private final int maxMessages;
    private final int snipHeadKeep;
    private final int keepRecent;
    private final int minPlaceholderLen;
    private final int maxToolResultBytes;
    private final Path taskOutputDir;
    private final int summaryHeadKeep;
    private final int summaryTailKeep;
    private final Path transcriptDir;
    private final int summaryMaxChars;

    /** 默认值构造器(生产用)。*/
    public CompactConfig() {
        this(50, 3, 3, 120,
                10000, defaultTaskOutputDir(),
                3, 10, defaultTranscriptDir(), 500);
    }

    /**
     * 4 参构造器(向后兼容 L1+L2 测试)。
     * L3/L4 字段走默认值。
     */
    public CompactConfig(int maxMessages, int snipHeadKeep, int keepRecent, int minPlaceholderLen) {
        this(maxMessages, snipHeadKeep, keepRecent, minPlaceholderLen,
                10000, defaultTaskOutputDir(),
                3, 10, defaultTranscriptDir(), 500);
    }

    /**
     * 6 参构造器(L3 测试用)。
     * L4 字段走默认值。
     */
    public CompactConfig(int maxMessages, int snipHeadKeep, int keepRecent, int minPlaceholderLen,
                         int maxToolResultBytes, Path taskOutputDir) {
        this(maxMessages, snipHeadKeep, keepRecent, minPlaceholderLen,
                maxToolResultBytes, taskOutputDir,
                3, 10, defaultTranscriptDir(), 500);
    }

    /** 10 参全参构造器(L4 测试用)。*/
    public CompactConfig(int maxMessages, int snipHeadKeep, int keepRecent, int minPlaceholderLen,
                         int maxToolResultBytes, Path taskOutputDir,
                         int summaryHeadKeep, int summaryTailKeep, Path transcriptDir,
                         int summaryMaxChars) {
        if (snipHeadKeep < 0 || snipHeadKeep >= maxMessages) {
            throw new IllegalArgumentException(
                    "snipHeadKeep must be in [0, maxMessages); got snipHeadKeep="
                            + snipHeadKeep + ", maxMessages=" + maxMessages);
        }
        if (keepRecent < 0) {
            throw new IllegalArgumentException("keepRecent must be >= 0; got " + keepRecent);
        }
        if (minPlaceholderLen < 0) {
            throw new IllegalArgumentException("minPlaceholderLen must be >= 0; got " + minPlaceholderLen);
        }
        if (maxToolResultBytes < 0) {
            throw new IllegalArgumentException("maxToolResultBytes must be >= 0; got " + maxToolResultBytes);
        }
        if (taskOutputDir == null) {
            throw new IllegalArgumentException("taskOutputDir must not be null");
        }
        if (summaryHeadKeep < 0) {
            throw new IllegalArgumentException("summaryHeadKeep must be >= 0; got " + summaryHeadKeep);
        }
        if (summaryTailKeep < 0) {
            throw new IllegalArgumentException("summaryTailKeep must be >= 0; got " + summaryTailKeep);
        }
        if (transcriptDir == null) {
            throw new IllegalArgumentException("transcriptDir must not be null");
        }
        if (summaryMaxChars <= 0) {
            throw new IllegalArgumentException("summaryMaxChars must be > 0; got " + summaryMaxChars);
        }
        this.maxMessages = maxMessages;
        this.snipHeadKeep = snipHeadKeep;
        this.keepRecent = keepRecent;
        this.minPlaceholderLen = minPlaceholderLen;
        this.maxToolResultBytes = maxToolResultBytes;
        this.taskOutputDir = taskOutputDir;
        this.summaryHeadKeep = summaryHeadKeep;
        this.summaryTailKeep = summaryTailKeep;
        this.transcriptDir = transcriptDir;
        this.summaryMaxChars = summaryMaxChars;
    }

    /**
     * cwd 下的 task_outputs/tool-results/ 默认路径。
     *
     * <p>包级公开:让 {@link com.xilidou.jooj.compact.CompactConfiguration#compactConfig}
     * 装配时复用,避免重复声明。
     */
    public static Path defaultTaskOutputDir() {
        return Paths.get(System.getProperty("user.dir"), ".task_outputs", "tool-results");
    }

    /**
     * cwd 下的 transcripts/ 默认路径。
     *
     * <p>包级公开理由同 {@link #defaultTaskOutputDir()}。
     */
    public static Path defaultTranscriptDir() {
        return Paths.get(System.getProperty("user.dir"), ".transcripts");
    }

    public int maxMessages() {
        return maxMessages;
    }

    public int snipHeadKeep() {
        return snipHeadKeep;
    }

    public int keepRecent() {
        return keepRecent;
    }

    public int minPlaceholderLen() {
        return minPlaceholderLen;
    }

    public int maxToolResultBytes() {
        return maxToolResultBytes;
    }

    public Path taskOutputDir() {
        return taskOutputDir;
    }

    public int summaryHeadKeep() {
        return summaryHeadKeep;
    }

    public int summaryTailKeep() {
        return summaryTailKeep;
    }

    public Path transcriptDir() {
        return transcriptDir;
    }

    public int summaryMaxChars() {
        return summaryMaxChars;
    }
}
