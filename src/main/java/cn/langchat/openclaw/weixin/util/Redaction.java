package cn.langchat.openclaw.weixin.util;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class Redaction {
    private Redaction() {
    }

    public static String redactToken(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        int len = value.length();
        if (len <= 8) {
            return "***";
        }
        return value.substring(0, 4) + "***" + value.substring(len - 4);
    }

    public static String redactJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        return raw
            .replaceAll("(?i)\\\"(token|bot_token|context_token|authorization)\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", "\\\"$1\\\":\\\"***\\\"")
            .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._\\-]+", "Bearer ***");
    }

    public static String redactUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        return url.replaceAll("(?i)(token|authorization|context_token|encrypted_query_param)=([^&]+)", "$1=***");
    }
}
