package cn.langchat.openclaw.weixin.monitor;

import cn.langchat.openclaw.weixin.api.WeixinApiClient;
import cn.langchat.openclaw.weixin.media.CdnMediaDownloader;
import cn.langchat.openclaw.weixin.model.GetUpdatesResponse;
import cn.langchat.openclaw.weixin.model.MediaRef;
import cn.langchat.openclaw.weixin.model.MessageItem;
import cn.langchat.openclaw.weixin.model.WeixinMessage;
import cn.langchat.openclaw.weixin.storage.FileContextTokenStore;
import cn.langchat.openclaw.weixin.storage.FileSyncCursorStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class WeixinLongPollMonitor {
    private static final Duration DEFAULT_LONG_POLL_TIMEOUT = Duration.ofSeconds(35);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);
    private static final Duration BACKOFF_DELAY = Duration.ofSeconds(30);

    private final String accountId;
    private final WeixinApiClient apiClient;
    private final FileSyncCursorStore syncCursorStore;
    private final FileContextTokenStore contextTokenStore;
    private final WeixinSessionGuard sessionGuard;
    private final InboundMessageHandler handler;
    private final InboundMessageEventHandler eventHandler;
    private final CdnMediaDownloader mediaDownloader;
    private final Path inboundMediaDir;
    private final MonitorLogHandler logHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public WeixinLongPollMonitor(
        String accountId,
        WeixinApiClient apiClient,
        FileSyncCursorStore syncCursorStore,
        FileContextTokenStore contextTokenStore,
        WeixinSessionGuard sessionGuard,
        InboundMessageHandler handler
    ) {
        this(accountId, apiClient, syncCursorStore, contextTokenStore, sessionGuard, handler, null, null, null, null);
    }

    public WeixinLongPollMonitor(
        String accountId,
        WeixinApiClient apiClient,
        FileSyncCursorStore syncCursorStore,
        FileContextTokenStore contextTokenStore,
        WeixinSessionGuard sessionGuard,
        InboundMessageHandler handler,
        InboundMessageEventHandler eventHandler,
        CdnMediaDownloader mediaDownloader,
        Path inboundMediaDir,
        MonitorLogHandler logHandler
    ) {
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.syncCursorStore = Objects.requireNonNull(syncCursorStore, "syncCursorStore");
        this.contextTokenStore = Objects.requireNonNull(contextTokenStore, "contextTokenStore");
        this.sessionGuard = Objects.requireNonNull(sessionGuard, "sessionGuard");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.eventHandler = eventHandler;
        this.mediaDownloader = mediaDownloader;
        this.inboundMediaDir = inboundMediaDir;
        this.logHandler = logHandler;
    }

    public void stop() {
        running.set(false);
        log("info", "monitor stopping");
    }

    public void runLoop() {
        contextTokenStore.restore(accountId);
        String cursor = syncCursorStore.load(accountId).orElse("");
        Duration nextTimeout = DEFAULT_LONG_POLL_TIMEOUT;
        int consecutiveFailures = 0;
        long pollCount = 0L;
        running.set(true);
        log("info", "monitor started, accountId=" + accountId + ", cursorBytes=" + cursor.length());

        while (running.get()) {
            try {
                sessionGuard.assertActive(accountId);
                GetUpdatesResponse response = apiClient.getUpdates(cursor, nextTimeout);
                pollCount++;

                if (response.longPollingTimeoutMs() != null && response.longPollingTimeoutMs() > 0) {
                    nextTimeout = Duration.ofMillis(response.longPollingTimeoutMs());
                }

                if (!response.isSuccess()) {
                    if (response.isSessionExpired()) {
                        sessionGuard.pause(accountId);
                        long sleepMs = sessionGuard.remainingPauseMs(accountId);
                        log("warn", "session expired, paused for " + Math.max(1, Math.round(sleepMs / 60000.0)) + " min");
                        sleep(Duration.ofMillis(sleepMs));
                        continue;
                    }
                    consecutiveFailures++;
                    log("warn", "getUpdates failed ret=" + response.ret() + " errCode=" + response.errCode() + " errMsg=" + response.errMsg() + " failures=" + consecutiveFailures);
                    if (consecutiveFailures >= 3) {
                        consecutiveFailures = 0;
                        sleep(BACKOFF_DELAY);
                    } else {
                        sleep(RETRY_DELAY);
                    }
                    continue;
                }

                consecutiveFailures = 0;

                if (response.getUpdatesBuf() != null && !response.getUpdatesBuf().isBlank()) {
                    cursor = response.getUpdatesBuf();
                    syncCursorStore.save(accountId, cursor);
                }

                if ((pollCount % 10) == 0 && (response.messages() == null || response.messages().isEmpty())) {
                    log("debug", "poll heartbeat: no new message, pollCount=" + pollCount);
                }

                for (WeixinMessage message : response.messages()) {
                    if (message.contextToken() != null && message.fromUserId() != null && !message.fromUserId().isBlank()) {
                        contextTokenStore.put(accountId, message.fromUserId(), message.contextToken());
                    }
                    handler.onMessage(message);
                    if (eventHandler != null) {
                        eventHandler.onEvent(toEvent(message));
                    }
                }
            } catch (Exception ex) {
                consecutiveFailures++;
                log("error", "monitor loop error: " + ex.getMessage() + " failures=" + consecutiveFailures);
                if (consecutiveFailures >= 3) {
                    consecutiveFailures = 0;
                    sleep(BACKOFF_DELAY);
                } else {
                    sleep(RETRY_DELAY);
                }
            }
        }
    }

    private InboundMessageEvent toEvent(WeixinMessage message) {
        if (mediaDownloader == null || inboundMediaDir == null) {
            return new InboundMessageEvent(message, null, null);
        }
        try {
            MessageItem item = message.firstMediaItem().orElse(null);
            if (item == null) {
                return new InboundMessageEvent(message, null, null);
            }
            if (item.type() == MessageItem.TYPE_VOICE && item.voiceText() != null && !item.voiceText().isBlank()) {
                return new InboundMessageEvent(message, null, null);
            }
            MediaRef media = item.media();
            if (media == null) {
                return new InboundMessageEvent(message, null, null);
            }
            String encryptedQueryParam = media.encryptQueryParam();
            String fullUrl = media.fullUrl();
            if ((encryptedQueryParam == null || encryptedQueryParam.isBlank()) && (fullUrl == null || fullUrl.isBlank())) {
                return new InboundMessageEvent(message, null, null);
            }

            byte[] data;
            String aesKeyBase64 = resolveAesKeyBase64(item, media);
            if (aesKeyBase64 != null && !aesKeyBase64.isBlank()) {
                data = mediaDownloader.downloadAndDecrypt(encryptedQueryParam, aesKeyBase64, fullUrl);
            } else {
                data = mediaDownloader.downloadPlain(encryptedQueryParam, fullUrl);
            }

            Path target = inboundMediaDir.resolve(accountId + "-" + System.currentTimeMillis() + "-" + ThreadLocalRandom.current().nextInt(1000, 9999) + extensionForItem(item));
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            log("debug", "inbound media saved: " + target);
            return new InboundMessageEvent(message, target, mediaTypeForItem(item));
        } catch (Exception ex) {
            log("warn", "inbound media decode failed: " + ex.getMessage());
            return new InboundMessageEvent(message, null, null);
        }
    }

    private static String resolveAesKeyBase64(MessageItem item, MediaRef media) {
        if (item.type() == MessageItem.TYPE_IMAGE && item.imageAesKeyHex() != null && !item.imageAesKeyHex().isBlank()) {
            try {
                byte[] raw = java.util.HexFormat.of().parseHex(item.imageAesKeyHex());
                return Base64.getEncoder().encodeToString(raw);
            } catch (IllegalArgumentException ignore) {
                // fallback to media.aes_key
            }
        }
        return media.aesKey();
    }

    private static String extensionForItem(MessageItem item) {
        return switch (item.type()) {
            case MessageItem.TYPE_IMAGE -> ".img";
            case MessageItem.TYPE_VIDEO -> ".mp4";
            case MessageItem.TYPE_VOICE -> ".silk";
            case MessageItem.TYPE_FILE -> extensionFromFileName(item.fileName());
            default -> ".bin";
        };
    }

    private static String extensionFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return ".bin";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot);
            if (ext.length() <= 12) {
                return ext;
            }
        }
        return ".bin";
    }

    private static String mediaTypeForItem(MessageItem item) {
        return switch (item.type()) {
            case MessageItem.TYPE_IMAGE -> "image/*";
            case MessageItem.TYPE_VIDEO -> "video/mp4";
            case MessageItem.TYPE_VOICE -> "audio/silk";
            case MessageItem.TYPE_FILE -> "application/octet-stream";
            default -> null;
        };
    }

    private void log(String level, String message) {
        if (logHandler != null) {
            logHandler.onLog(level, message);
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(Math.max(0L, d.toMillis()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
