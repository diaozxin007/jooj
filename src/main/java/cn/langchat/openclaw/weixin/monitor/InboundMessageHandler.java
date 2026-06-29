package cn.langchat.openclaw.weixin.monitor;

import cn.langchat.openclaw.weixin.model.WeixinMessage;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
@FunctionalInterface
public interface InboundMessageHandler {
    void onMessage(WeixinMessage message);
}
