package cn.langchat.openclaw.weixin.model;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public enum TypingStatus {
    TYPING(1),
    CANCEL(2);

    private final int code;

    TypingStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
