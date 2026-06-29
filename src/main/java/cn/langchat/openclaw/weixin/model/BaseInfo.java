package cn.langchat.openclaw.weixin.model;

import java.util.Map;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record BaseInfo(String channelVersion) {
    public Map<String, Object> toMap() {
        return Map.of("channel_version", channelVersion);
    }
}
