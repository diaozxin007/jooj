package cn.langchat.openclaw.weixin.auth;

import cn.langchat.openclaw.weixin.storage.FileAccountStore;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class QrLoginFlowService {
    private static final long ACTIVE_LOGIN_TTL_MS = Duration.ofMinutes(5).toMillis();
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(35);
    private static final int MAX_QR_REFRESH_COUNT = 3;

    private final QrLoginClient qrLoginClient;
    private final FileAccountStore accountStore;
    private final Map<String, QrLoginSession> sessions = new ConcurrentHashMap<>();

    public QrLoginFlowService(QrLoginClient qrLoginClient, FileAccountStore accountStore) {
        this.qrLoginClient = qrLoginClient;
        this.accountStore = accountStore;
    }

    public QrLoginSession start(String accountId, String botType, boolean force) {
        String sessionKey = (accountId == null || accountId.isBlank()) ? UUID.randomUUID().toString() : accountId;
        QrLoginSession existing = sessions.get(sessionKey);
        if (!force && existing != null && isFresh(existing.startedAtMs())) {
            return existing;
        }

        QrLoginStartResult result = qrLoginClient.start(botType);
        QrLoginSession session = new QrLoginSession(
            sessionKey,
            result.qrcode(),
            result.qrcodeUrl(),
            System.currentTimeMillis(),
            null
        );
        sessions.put(sessionKey, session);
        return session;
    }

    public QrLoginFlowResult waitForConfirm(String sessionKey, Duration timeout, String botType) {
        QrLoginSession current = sessions.get(sessionKey);
        if (current == null) {
            return new QrLoginFlowResult(false, "当前没有进行中的登录，请先发起登录。", null, null, null, null);
        }
        if (!isFresh(current.startedAtMs())) {
            sessions.remove(sessionKey);
            return new QrLoginFlowResult(false, "二维码已过期，请重新生成。", null, null, null, null);
        }

        long timeoutMs = Math.max(timeout == null ? Duration.ofMinutes(8).toMillis() : timeout.toMillis(), 1000L);
        long deadline = System.currentTimeMillis() + timeoutMs;
        int refreshCount = 1;
        String currentBaseUrl = null;

        while (System.currentTimeMillis() < deadline) {
            QrLoginWaitResult status = qrLoginClient.pollStatus(current.qrcode(), POLL_TIMEOUT, currentBaseUrl);
            String s = status.status() == null ? "wait" : status.status();

            switch (s) {
                case "wait", "scaned" -> {
                    sleep(1000L);
                }
                case "scaned_but_redirect" -> {
                    if (status.redirectHost() != null && !status.redirectHost().isBlank()) {
                        currentBaseUrl = "https://" + status.redirectHost().trim();
                    }
                    sleep(1000L);
                }
                case "expired" -> {
                    refreshCount++;
                    if (refreshCount > MAX_QR_REFRESH_COUNT) {
                        sessions.remove(sessionKey);
                        return new QrLoginFlowResult(false, "登录超时：二维码多次过期，请重新开始登录流程。", null, null, null, null);
                    }
                    QrLoginStartResult refreshed = qrLoginClient.start(botType);
                    current = new QrLoginSession(
                        sessionKey,
                        refreshed.qrcode(),
                        refreshed.qrcodeUrl(),
                        System.currentTimeMillis(),
                        currentBaseUrl
                    );
                    sessions.put(sessionKey, current);
                    sleep(1000L);
                }
                case "confirmed" -> {
                    sessions.remove(sessionKey);
                    if (status.accountId() == null || status.accountId().isBlank()) {
                        return new QrLoginFlowResult(false, "登录失败：服务器未返回 ilink_bot_id。", null, null, null, null);
                    }
                    accountStore.save(status.accountId(), status.botToken(), status.baseUrl(), status.userId());
                    return new QrLoginFlowResult(true, "✅ 与微信连接成功！", status.botToken(), status.accountId(), status.baseUrl(), status.userId());
                }
                default -> {
                    sleep(1000L);
                }
            }
        }

        sessions.remove(sessionKey);
        return new QrLoginFlowResult(false, "登录超时：等待二维码确认超时。", null, null, null, null);
    }

    private static boolean isFresh(long startedAtMs) {
        return System.currentTimeMillis() - startedAtMs < ACTIVE_LOGIN_TTL_MS;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
