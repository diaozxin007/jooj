package cn.langchat.openclaw.weixin.model;

import cn.langchat.openclaw.weixin.util.MapValues;

import java.util.Map;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record GetConfigResponse(
    Integer ret,
    String errMsg,
    String typingTicket,
    Map<String, Object> raw
) {
    public static GetConfigResponse fromMap(Map<String, Object> map) {
        return new GetConfigResponse(
            MapValues.integer(map, "ret"),
            MapValues.string(map, "errmsg"),
            MapValues.string(map, "typing_ticket"),
            Map.copyOf(map)
        );
    }
}
