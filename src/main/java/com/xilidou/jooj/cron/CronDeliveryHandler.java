package com.xilidou.jooj.cron;

import com.xilidou.jooj.channel.ChannelDeliverer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * CronDeliveryHandler —— cron 触发的 turn 完成后,按 job 自描述的 deliveryType 分派 reply。
 *
 * <h3>Hermes 参考实现</h3>
 *
 * <p>Hermes 里 {@code _deliver_result(job, content, adapters, loop)} 是 scheduler 独立函数,
 * conversation loop 完全不知道 delivery 存在。jooj 之前把这个函数放在 AgentLoopHarness 里
 * 是历史遗留,借 s22 架构审查搬到 cron 模块。
 *
 * <h3>三种 deliveryType(job self-describing,s21 Demo 20)</h3>
 *
 * <ul>
 *   <li>{@code channel} —— 通过 ChannelDeliverer 发给外部 channel(微信/Discord/etc)。
 *       channel 包缺席(纯 CLI 模式)时 warn + skip,不报错</li>
 *   <li>{@code team} —— 用 MessageBus 投递给 lead / teammate。当前只是占位,LLM 还没生成 team cron</li>
 *   <li>{@code none} —— 什么都不做(job 是纯本地脚本,结果只落 transcript / session,不外发)</li>
 * </ul>
 *
 * <h3>不做的事</h3>
 *
 * <ul>
 *   <li>不做 delivery 重试 —— 一次失败就 warn log。cron job 本身是"下次 fire 重试"语义,
 *       delivery 单次失败不影响下次</li>
 *   <li>不修改 job 状态(比如 markDelivered) —— job 只知道自己的 schedule,delivery 是 orthogonal 事件</li>
 *   <li>不 wrap reply / 加 header —— Hermes 有 {@code wrap_response} 配置,jooj 目前没这需求,
 *       reply 是什么就发什么</li>
 * </ul>
 */
@Component
@Slf4j
public class CronDeliveryHandler {

    /**
     * ObjectProvider 让 channel 包不存在时(纯 CLI 模式)也能装配 handler,
     * deliveryType=channel 时 deliverer 缺席就 log warn + skip。
     */
    private final ObjectProvider<ChannelDeliverer> channelDelivererProvider;

    public CronDeliveryHandler(ObjectProvider<ChannelDeliverer> channelDelivererProvider) {
        this.channelDelivererProvider = channelDelivererProvider;
    }

    /**
     * 按 cron job 自描述的 deliveryType 路由 LLM 回复。
     *
     * <p>fire 时**只读 cron job 自身**,不查任何旁路状态。所有路由决策(target channel / peer)
     * 在 schedule 时已 freeze 进 job(见 CronTool.doSchedule + CronService.schedule)。
     *
     * @param job   cron job 自描述含 deliveryType + channel + peerId
     * @param reply lead-agent 本轮回复的纯文本(null / blank 时 channel 分派会 skip)
     */
    public void deliver(CronJob job, String reply) {
        String type = job.getDeliveryType();
        if (type == null) type = "none";

        switch (type) {
            case "channel" -> {
                if (reply == null || reply.isBlank()) {
                    log.info("[Cron] job {} channel-delivery skipped: no assistant text", job.getId());
                    return;
                }
                String channel = job.getChannel();
                String peerId = job.getPeerId();
                if (channel == null || peerId == null) {
                    log.warn("[Cron] job {} deliveryType=channel but missing channel/peerId, dropped",
                            job.getId());
                    return;
                }
                ChannelDeliverer deliverer = channelDelivererProvider != null
                        ? channelDelivererProvider.getIfAvailable() : null;
                if (deliverer == null) {
                    log.warn("[Cron] job {} deliveryType=channel but no ChannelDeliverer wired " +
                            "(jooj.weixin.enabled=false?), dropped", job.getId());
                    return;
                }
                boolean ok = deliverer.deliver(channel, peerId, reply);
                log.info("[Cron] job {} delivered to channel={} peer={}: {}",
                        job.getId(), channel, peerId, ok);
            }
            case "team" -> {
                // Tier B 后续:用 messageBus 投递给 alice/bob;当前 LLM 还没自己生成 team cron
                log.warn("[Cron] job {} deliveryType=team not yet implemented", job.getId());
            }
            case "none" -> {
                log.debug("[Cron] job {} deliveryType=none, no outbound delivery", job.getId());
            }
            default ->
                    log.warn("[Cron] job {} unknown deliveryType '{}', dropped", job.getId(), type);
        }
    }
}
