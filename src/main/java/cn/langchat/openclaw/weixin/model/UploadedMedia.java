package cn.langchat.openclaw.weixin.model;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record UploadedMedia(
    String fileKey,
    String downloadEncryptedQueryParam,
    String aesKeyHex,
    long plainSize,
    long cipherSize
) {
}
