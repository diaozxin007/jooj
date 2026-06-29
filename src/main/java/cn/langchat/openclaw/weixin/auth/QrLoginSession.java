package cn.langchat.openclaw.weixin.auth;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record QrLoginSession(
    String sessionKey,
    String qrcode,
    String qrcodeUrl,
    long startedAtMs,
    String currentBaseUrl
) {
}
