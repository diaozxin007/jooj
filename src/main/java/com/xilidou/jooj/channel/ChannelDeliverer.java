package com.xilidou.jooj.channel;

/**
 * ChannelDeliverer —— harness / cron 等"jooj 主动发起的 turn"投递抽象(s21 Demo 20)。
 *
 * <h3>解决什么</h3>
 *
 * <p>{@link AgentLoopHarness#processCronTriggers} 在 cron 触发后跑完 agentLoop,需要把
 * LLM 回复回写到 channel(微信/Discord/...)。但 harness 在 agent 包,channel 实现在
 * channel/weixin 包 —— 直接依赖会导致包循环。
 *
 * <p>引这个接口:harness 只看到接口,实现方({@link InboundDispatcher})实现接口。Spring
 * 装配时 harness 注入 {@code ObjectProvider<ChannelDeliverer>},没 channel 包时优雅降级。
 *
 * <h3>跟 Demo 19 listener 模式的区别</h3>
 *
 * <p>Demo 19 用 {@code BiConsumer<sessionId, historyBefore>} 让 dispatcher 反查路由 +
 * 反查 reply。**Demo 20 重写**:cron job 自描述(deliveryType + channel + peerId),harness
 * 直接告诉 deliverer "送到 channel=X peer=Y",dispatcher 降级为单纯执行器。
 */
public interface ChannelDeliverer {

    /**
     * 把文本投递到指定 channel + peer。
     *
     * @param channel 渠道名,如 {@code "weixin"} / {@code "discord"}
     * @param peerId  对端 raw id(原始形式,不是 sanitized),如 {@code "xxx@im.wechat"}
     * @param text    要发的内容(LLM 回复或其他)
     * @return true = 成功投递; false = channel 没注册 / peer 不可达 / sendOutbound 抛异常
     */
    boolean deliver(String channel, String peerId, String text);
}
