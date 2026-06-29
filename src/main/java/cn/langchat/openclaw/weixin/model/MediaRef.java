package cn.langchat.openclaw.weixin.model;

import cn.langchat.openclaw.weixin.util.MapValues;

import java.util.Map;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record MediaRef(
    String encryptQueryParam,
    String aesKey,
    Integer encryptType,
    String fullUrl,
    Map<String, Object> raw
) {
    public static MediaRef fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        return new MediaRef(
            MapValues.string(map, "encrypt_query_param"),
            MapValues.string(map, "aes_key"),
            MapValues.integer(map, "encrypt_type"),
            MapValues.string(map, "full_url"),
            Map.copyOf(map)
        );
    }
}
