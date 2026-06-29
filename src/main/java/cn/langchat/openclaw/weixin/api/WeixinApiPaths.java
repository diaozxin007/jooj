package cn.langchat.openclaw.weixin.api;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class WeixinApiPaths {
    public static final String GET_UPDATES = "/ilink/bot/getupdates";
    public static final String SEND_MESSAGE = "/ilink/bot/sendmessage";
    public static final String GET_UPLOAD_URL = "/ilink/bot/getuploadurl";
    public static final String GET_CONFIG = "/ilink/bot/getconfig";
    public static final String SEND_TYPING = "/ilink/bot/sendtyping";

    public static final String QR_START = "/ilink/bot/get_bot_qrcode";
    public static final String QR_STATUS = "/ilink/bot/get_qrcode_status";

    private WeixinApiPaths() {
    }
}
