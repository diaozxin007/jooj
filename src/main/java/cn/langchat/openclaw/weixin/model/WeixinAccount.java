package cn.langchat.openclaw.weixin.model;

import cn.langchat.openclaw.weixin.util.MapValues;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record WeixinAccount(
    String accountId,
    String token,
    String baseUrl,
    String userId,
    String savedAt
) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("baseUrl", baseUrl);
        if (userId != null && !userId.isBlank()) {
            m.put("userId", userId);
        }
        m.put("savedAt", savedAt);
        return m;
    }

    public static WeixinAccount fromMap(String accountId, Map<String, Object> map) {
        return new WeixinAccount(
            accountId,
            MapValues.string(map, "token"),
            MapValues.string(map, "baseUrl"),
            MapValues.string(map, "userId"),
            MapValues.string(map, "savedAt")
        );
    }
}
