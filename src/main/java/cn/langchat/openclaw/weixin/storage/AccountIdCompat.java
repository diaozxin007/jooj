package cn.langchat.openclaw.weixin.storage;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class AccountIdCompat {
    private AccountIdCompat() {
    }

    public static String normalizeLikeTs(String raw) {
        if (raw == null) {
            return null;
        }
        String n = raw.trim();
        if (n.isEmpty()) {
            return n;
        }
        n = n.replace('@', '-').replace('.', '-');
        while (n.contains("--")) {
            n = n.replace("--", "-");
        }
        return n;
    }

    public static String deriveRawAccountId(String normalizedId) {
        if (normalizedId == null || normalizedId.isBlank()) {
            return null;
        }
        if (normalizedId.endsWith("-im-bot")) {
            return normalizedId.substring(0, normalizedId.length() - 7) + "@im.bot";
        }
        if (normalizedId.endsWith("-im-wechat")) {
            return normalizedId.substring(0, normalizedId.length() - 10) + "@im.wechat";
        }
        return null;
    }
}
