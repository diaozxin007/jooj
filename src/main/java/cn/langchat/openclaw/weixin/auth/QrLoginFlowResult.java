package cn.langchat.openclaw.weixin.auth;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record QrLoginFlowResult(
    boolean connected,
    String message,
    String botToken,
    String accountId,
    String baseUrl,
    String userId
) {
}
