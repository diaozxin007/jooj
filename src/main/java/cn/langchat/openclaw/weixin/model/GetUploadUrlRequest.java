package cn.langchat.openclaw.weixin.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record GetUploadUrlRequest(
    String fileKey,
    UploadMediaType mediaType,
    String toUserId,
    long rawSize,
    String rawFileMd5,
    long fileSize,
    Long thumbRawSize,
    String thumbRawFileMd5,
    Long thumbFileSize,
    Boolean noNeedThumb,
    String aesKeyHex
) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filekey", fileKey);
        m.put("media_type", mediaType.code());
        m.put("to_user_id", toUserId);
        m.put("rawsize", rawSize);
        m.put("rawfilemd5", rawFileMd5);
        m.put("filesize", fileSize);
        if (thumbRawSize != null) {
            m.put("thumb_rawsize", thumbRawSize);
        }
        if (thumbRawFileMd5 != null && !thumbRawFileMd5.isBlank()) {
            m.put("thumb_rawfilemd5", thumbRawFileMd5);
        }
        if (thumbFileSize != null) {
            m.put("thumb_filesize", thumbFileSize);
        }
        if (noNeedThumb != null) {
            m.put("no_need_thumb", noNeedThumb);
        }
        if (aesKeyHex != null && !aesKeyHex.isBlank()) {
            m.put("aeskey", aesKeyHex);
        }
        return m;
    }
}
