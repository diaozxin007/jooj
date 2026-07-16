package com.xilidou.jooj.tui;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TUI channel 配置项(s23 P2)。
 *
 * <p>仅在 {@code jooj.tui.*} 命名空间下的 yml 段,{@link org.springframework.context.annotation.Profile @Profile("tui")}
 * 未启用时,虽然 bean 装配也会执行(ConfigurationProperties 不带 profile),但整个 tui/
 * 域的其他 bean 都被 profile 门控,所以配置值不会被使用。这样避免 profile 未启用时
 * 出现 "unknown property" 类的绑定错误。
 */
@ConfigurationProperties(prefix = "jooj.tui")
public class TuiProperties {

    /**
     * 主题:{@code default / dark / light / none}。
     *
     * <p>{@code none} 表示不发送任何 ANSI escape;自动降级触发条件:
     * {@code NO_COLOR} 环境变量存在,或 stdout 非 TTY(pipe / redirect 场景)。
     * 具体降级由 {@code TuiTerminal} 实施。
     */
    private String theme = "default";

    /**
     * 是否展示 thinking token(agent 内部推理过程)。默认关 —— thinking token 量大
     * 通常不是用户想看的内容;调试或洞察 agent 决策路径时开。
     */
    private boolean showThinking = false;

    /**
     * tool 输出预览最多显示几行,超出折叠为 {@code [+N 行]}。太多会挤压对话可见区。
     */
    private int maxPreviewLines = 3;

    /**
     * TUI 内部 query 队列上限(s23 P5)。用户在 worker 忙时敲的 query 排队,超过上限
     * 直接拒绝并提示。默认 5:少量"连珠炮"(比如一口气问 3 个相关问题让 agent 依次处理)
     * 可缓存;超过后拒绝避免 agent 换话题困惑。
     *
     * <p>Web / weixin 有各自并发模型,本参数**只影响 TUI channel**,不下沉到 InboundDispatcher。
     */
    private int queueCapacity = 5;

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public boolean isShowThinking() { return showThinking; }
    public void setShowThinking(boolean showThinking) { this.showThinking = showThinking; }

    public int getMaxPreviewLines() { return maxPreviewLines; }
    public void setMaxPreviewLines(int maxPreviewLines) { this.maxPreviewLines = maxPreviewLines; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
}
