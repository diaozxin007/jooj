package com.xilidou.jooj.weixin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Weixin 渠道**配置**(s21 Demo 16.5 后)—— application.yml 里 {@code jooj.weixin.*}。
 *
 * <h3>定位:配置 vs 状态</h3>
 *
 * <p>这里只放**配置**(用户想让 jooj 怎么跑,jooj 启动后不会自己改):
 * <ul>
 *   <li>{@code enabled} — 开关</li>
 *   <li>{@code botAgent} — 透传给腾讯 server 的标识</li>
 *   <li>{@code dispatchPoolSize} — IM 多 peer 并发度</li>
 * </ul>
 *
 * <p>**状态**(jooj 跑起来才知道的、可能 jooj 自己改的)在 {@link WeixinAccountState}:
 * <ul>
 *   <li>active accountId —— 扫码后由 SDK 给随机 hex,落 {@code ~/.jooj/weixin/state.json}</li>
 * </ul>
 *
 * <p>之前把 accountId 也塞 yml 是设计错配 —— accountId 用户事先不可能知道(腾讯 server 给的随机 hex),
 * 写 yml 没意义。Demo 16.5 拆开,yml 只剩真正的配置。
 */
@ConfigurationProperties("jooj.weixin")
public class WeixinProperties {

    /** 是否启用 weixin 集成。默认 false(主代码 + 测试不受影响)。 */
    private boolean enabled = false;

    /** BotAgent 标识,SDK 透传给腾讯 server 用于审计。 */
    private String botAgent = "jooj";

    /**
     * Dispatch worker pool 线程数 —— 决定不同微信 peer 能否真正并发跑 LLM(s21 Demo 14)。
     *
     * <p>压测数据:poolSize=1 串行 6056ms,poolSize=2 并行 3019ms(100% 提速),
     * 见 {@code SessionConcurrencyStressTest}。
     */
    private int dispatchPoolSize = 4;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBotAgent() { return botAgent; }
    public void setBotAgent(String botAgent) { this.botAgent = botAgent; }

    public int getDispatchPoolSize() { return dispatchPoolSize; }
    public void setDispatchPoolSize(int dispatchPoolSize) { this.dispatchPoolSize = dispatchPoolSize; }
}
