package cn.langchat.openclaw.weixin.monitor;

import cn.langchat.openclaw.weixin.model.WeixinMessage;

import java.nio.file.Path;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record InboundMessageEvent(
    WeixinMessage message,
    Path localMediaPath,
    String mediaType
) {
    public boolean hasMedia() {
        return localMediaPath != null;
    }
}
