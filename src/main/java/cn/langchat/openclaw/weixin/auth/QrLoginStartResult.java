package cn.langchat.openclaw.weixin.auth;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record QrLoginStartResult(
    String qrcode,
    String qrcodeUrl
) {
}
