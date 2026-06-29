package cn.langchat.openclaw.weixin.model;

import cn.langchat.openclaw.weixin.util.MapValues;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record WeixinMessage(
    Long seq,
    Long messageId,
    String fromUserId,
    String toUserId,
    Long createTimeMs,
    String contextToken,
    List<MessageItem> items,
    Map<String, Object> raw
) {
    public static WeixinMessage fromMap(Map<String, Object> map) {
        List<MessageItem> list = new ArrayList<>();
        for (Map<String, Object> it : MapValues.mapList(map, "item_list")) {
            MessageItem item = MessageItem.fromMap(it);
            if (item != null) {
                list.add(item);
            }
        }
        return new WeixinMessage(
            MapValues.longValue(map, "seq"),
            MapValues.longValue(map, "message_id"),
            MapValues.string(map, "from_user_id"),
            MapValues.string(map, "to_user_id"),
            MapValues.longValue(map, "create_time_ms"),
            MapValues.string(map, "context_token"),
            List.copyOf(list),
            Map.copyOf(map)
        );
    }

    public String textBody() {
        for (MessageItem item : items) {
            if (item.type() == MessageItem.TYPE_TEXT && item.text() != null) {
                return item.text();
            }
            if (item.type() == MessageItem.TYPE_VOICE && item.voiceText() != null) {
                return item.voiceText();
            }
        }
        return "";
    }

    public Optional<MessageItem> firstMediaItem() {
        return items.stream()
            .filter(it -> it.type() == MessageItem.TYPE_IMAGE
                || it.type() == MessageItem.TYPE_VIDEO
                || it.type() == MessageItem.TYPE_FILE
                || it.type() == MessageItem.TYPE_VOICE)
            .findFirst();
    }
}
