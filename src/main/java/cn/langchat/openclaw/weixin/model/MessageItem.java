package cn.langchat.openclaw.weixin.model;

import cn.langchat.openclaw.weixin.util.MapValues;

import java.util.Map;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record MessageItem(
    int type,
    String text,
    String voiceText,
    String fileName,
    String imageAesKeyHex,
    MediaRef media,
    MediaRef thumbMedia,
    Map<String, Object> raw
) {
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_IMAGE = 2;
    public static final int TYPE_VOICE = 3;
    public static final int TYPE_FILE = 4;
    public static final int TYPE_VIDEO = 5;

    public static MessageItem fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        int type = MapValues.optionalInteger(map, "type").orElse(0);
        Map<String, Object> textItem = MapValues.map(map, "text_item");
        Map<String, Object> imageItem = MapValues.map(map, "image_item");
        Map<String, Object> voiceItem = MapValues.map(map, "voice_item");
        Map<String, Object> fileItem = MapValues.map(map, "file_item");
        Map<String, Object> videoItem = MapValues.map(map, "video_item");

        MediaRef media = MediaRef.fromMap(resolveMediaMap(imageItem, voiceItem, fileItem, videoItem));
        MediaRef thumbMedia = MediaRef.fromMap(resolveThumbMediaMap(imageItem, videoItem));
        return new MessageItem(
            type,
            textItem == null ? null : MapValues.string(textItem, "text"),
            voiceItem == null ? null : MapValues.string(voiceItem, "text"),
            fileItem == null ? null : MapValues.string(fileItem, "file_name"),
            imageItem == null ? null : MapValues.string(imageItem, "aeskey"),
            media,
            thumbMedia,
            Map.copyOf(map)
        );
    }

    private static Map<String, Object> resolveMediaMap(
        Map<String, Object> imageItem,
        Map<String, Object> voiceItem,
        Map<String, Object> fileItem,
        Map<String, Object> videoItem
    ) {
        Map<String, Object> media = imageItem == null ? null : MapValues.map(imageItem, "media");
        if (media != null) {
            return media;
        }
        media = voiceItem == null ? null : MapValues.map(voiceItem, "media");
        if (media != null) {
            return media;
        }
        media = fileItem == null ? null : MapValues.map(fileItem, "media");
        if (media != null) {
            return media;
        }
        return videoItem == null ? null : MapValues.map(videoItem, "media");
    }

    private static Map<String, Object> resolveThumbMediaMap(
        Map<String, Object> imageItem,
        Map<String, Object> videoItem
    ) {
        Map<String, Object> thumb = imageItem == null ? null : MapValues.map(imageItem, "thumb_media");
        if (thumb != null) {
            return thumb;
        }
        return videoItem == null ? null : MapValues.map(videoItem, "thumb_media");
    }
}
