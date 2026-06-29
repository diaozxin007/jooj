package cn.langchat.openclaw.weixin.model;

import cn.langchat.openclaw.weixin.util.MapValues;

import java.util.Map;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record GetUploadUrlResponse(
    String uploadParam,
    String thumbUploadParam,
    String uploadFullUrl,
    Map<String, Object> raw
) {
    public static GetUploadUrlResponse fromMap(Map<String, Object> map) {
        return new GetUploadUrlResponse(
            MapValues.string(map, "upload_param"),
            MapValues.string(map, "thumb_upload_param"),
            MapValues.string(map, "upload_full_url"),
            Map.copyOf(map)
        );
    }
}
