package com.xilidou.jooj.channel;

/**
 * 入站消息 —— 跟具体渠道无关的消息载体。
 *
 * <p>Channel 实现把渠道私有的消息(WeixinMessage / DiscordMessage / ...)规范成这个,
 * 然后交给 {@link InboundDispatcher} 统一走 LLM。
 *
 * @param channel 渠道标识,如 "weixin"、"discord";路由 session id 时用
 * @param peerId  对端标识(联系人 / 群 id),per-peer session 路由的依据
 * @param peerName 对端可读名(用于日志 / SYSTEM 提示注入,可空)
 * @param text    消息文本内容(非文本类如图片,Channel 自行决定要不要喂 LLM)
 * @param messageId 渠道侧消息 id(去重 / 引用回复用,可空)
 */
public record ChannelMessage(
        String channel,
        String peerId,
        String peerName,
        String text,
        String messageId
) {
    public ChannelMessage {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
        if (peerId == null || peerId.isBlank()) {
            throw new IllegalArgumentException("peerId must not be blank");
        }
        if (text == null) text = "";
    }
}
