package cn.langchat.openclaw.weixin.auth;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public record QrLoginWaitResult(
    String status,
    String botToken,
    String accountId,
    String baseUrl,
    String userId,
    String redirectHost
) {
    public boolean confirmed() {
        return "confirmed".equalsIgnoreCase(status);
    }
}
