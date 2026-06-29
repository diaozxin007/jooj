package cn.langchat.openclaw.weixin.model;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public enum UploadMediaType {
    IMAGE(1),
    VIDEO(2),
    FILE(3),
    VOICE(4);

    private final int code;

    UploadMediaType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
