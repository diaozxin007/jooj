package cn.langchat.openclaw.weixin.model;

import cn.langchat.openclaw.weixin.monitor.WeixinSessionGuard;
import cn.langchat.openclaw.weixin.util.MapValues;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record GetUpdatesResponse(
    Integer ret,
    Integer errCode,
    String errMsg,
    List<WeixinMessage> messages,
    String getUpdatesBuf,
    Long longPollingTimeoutMs,
    Map<String, Object> raw
) {
    public static GetUpdatesResponse fromMap(Map<String, Object> map) {
        List<WeixinMessage> list = new ArrayList<>();
        for (Map<String, Object> msgMap : MapValues.mapList(map, "msgs")) {
            list.add(WeixinMessage.fromMap(msgMap));
        }
        return new GetUpdatesResponse(
            MapValues.integer(map, "ret"),
            MapValues.integer(map, "errcode"),
            MapValues.string(map, "errmsg"),
            List.copyOf(list),
            MapValues.string(map, "get_updates_buf"),
            MapValues.longValue(map, "longpolling_timeout_ms"),
            Map.copyOf(map)
        );
    }

    public boolean isSuccess() {
        return (ret == null || ret == 0) && (errCode == null || errCode == 0);
    }

    public boolean isSessionExpired() {
        return Integer.valueOf(WeixinSessionGuard.SESSION_EXPIRED_ERRCODE).equals(errCode)
            || Integer.valueOf(WeixinSessionGuard.SESSION_EXPIRED_ERRCODE).equals(ret);
    }
}
