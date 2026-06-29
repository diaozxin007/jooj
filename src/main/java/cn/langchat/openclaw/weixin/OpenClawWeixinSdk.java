package cn.langchat.openclaw.weixin;

import cn.langchat.openclaw.weixin.api.WeixinApiClient;
import cn.langchat.openclaw.weixin.api.WeixinClientConfig;
import cn.langchat.openclaw.weixin.api.WeixinConfigCache;
import cn.langchat.openclaw.weixin.auth.QrLoginClient;
import cn.langchat.openclaw.weixin.auth.QrLoginFlowService;
import cn.langchat.openclaw.weixin.media.CdnMediaDownloader;
import cn.langchat.openclaw.weixin.media.MediaUploadService;
import cn.langchat.openclaw.weixin.model.WeixinMessage;
import cn.langchat.openclaw.weixin.model.TypingStatus;
import cn.langchat.openclaw.weixin.monitor.InboundMessageEventHandler;
import cn.langchat.openclaw.weixin.monitor.InboundMessageHandler;
import cn.langchat.openclaw.weixin.monitor.MonitorLogHandler;
import cn.langchat.openclaw.weixin.monitor.WeixinLongPollMonitor;
import cn.langchat.openclaw.weixin.monitor.WeixinMonitorStream;
import cn.langchat.openclaw.weixin.monitor.WeixinSessionGuard;
import cn.langchat.openclaw.weixin.storage.AccountContextResolver;
import cn.langchat.openclaw.weixin.storage.FileAccountStore;
import cn.langchat.openclaw.weixin.storage.FileContextTokenStore;
import cn.langchat.openclaw.weixin.storage.FileSyncCursorStore;
import cn.langchat.openclaw.weixin.storage.StateDirectoryResolver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class OpenClawWeixinSdk {
    private final WeixinApiClient apiClient;
    private final QrLoginClient qrLoginClient;
    private final MediaUploadService mediaUploadService;
    private final WeixinConfigCache configCache;
    private final QrLoginFlowService qrLoginFlowService;
    private final FileAccountStore accountStore;
    private final FileContextTokenStore contextTokenStore;
    private final FileSyncCursorStore syncCursorStore;
    private final WeixinSessionGuard sessionGuard;
    private final CdnMediaDownloader mediaDownloader;

    public OpenClawWeixinSdk(WeixinClientConfig config) {
        this.apiClient = new WeixinApiClient(config);
        this.qrLoginClient = new QrLoginClient(apiClient);
        this.mediaUploadService = new MediaUploadService(apiClient);
        this.configCache = new WeixinConfigCache(apiClient);
        this.accountStore = new FileAccountStore();
        this.qrLoginFlowService = new QrLoginFlowService(qrLoginClient, accountStore);
        this.contextTokenStore = new FileContextTokenStore();
        this.syncCursorStore = new FileSyncCursorStore();
        this.sessionGuard = new WeixinSessionGuard();
        this.mediaDownloader = new CdnMediaDownloader(config.cdnBaseUrl());
    }

    public WeixinApiClient api() {
        return apiClient;
    }

    public QrLoginClient qr() {
        return qrLoginClient;
    }

    public MediaUploadService media() {
        return mediaUploadService;
    }

    public WeixinConfigCache configCache() {
        return configCache;
    }

    public QrLoginFlowService qrFlow() {
        return qrLoginFlowService;
    }

    public FileAccountStore accounts() {
        return accountStore;
    }

    public CdnMediaDownloader mediaDownloader() {
        return mediaDownloader;
    }

    public Optional<String> contextToken(String accountId, String userId) {
        return contextTokenStore.get(accountId, userId);
    }

    public void bindContextToken(String accountId, String userId, String contextToken) {
        contextTokenStore.put(accountId, userId, contextToken);
    }

    public Set<String> listKnownPeers(String accountId) {
        return contextTokenStore.listUserIds(accountId);
    }

    public String sendText(String accountId, String toUserId, String text) {
        sessionGuard.assertActive(accountId);
        String token = contextTokenStore.get(accountId, toUserId).orElse(null);
        return apiClient.sendTextMessage(toUserId, text, token);
    }

    public String sendText(String toUserId, String text) {
        String accountId = AccountContextResolver.resolveOutboundAccountId(accountStore.listAccountIds(), toUserId, contextTokenStore);
        return sendText(accountId, toUserId, text);
    }

    public List<String> sendTextStream(String accountId, String toUserId, Iterable<String> chunks, Duration interval) {
        sessionGuard.assertActive(accountId);
        String token = contextTokenStore.get(accountId, toUserId).orElse(null);
        return apiClient.sendTextStream(toUserId, chunks, token, interval);
    }

    public List<String> sendTextStream(String toUserId, Iterable<String> chunks, Duration interval) {
        String accountId = AccountContextResolver.resolveOutboundAccountId(accountStore.listAccountIds(), toUserId, contextTokenStore);
        return sendTextStream(accountId, toUserId, chunks, interval);
    }

    public String sendMedia(String accountId, String toUserId, Path file, String caption) {
        sessionGuard.assertActive(accountId);
        String token = contextTokenStore.get(accountId, toUserId).orElse(null);
        return mediaUploadService.sendMedia(file, toUserId, caption, token);
    }

    public String sendMedia(String toUserId, Path file, String caption) {
        String accountId = AccountContextResolver.resolveOutboundAccountId(accountStore.listAccountIds(), toUserId, contextTokenStore);
        return sendMedia(accountId, toUserId, file, caption);
    }

    public String sendMedia(String accountId, String toUserId, String mediaUrlOrPath, String caption) {
        sessionGuard.assertActive(accountId);
        String token = contextTokenStore.get(accountId, toUserId).orElse(null);
        return mediaUploadService.sendMedia(mediaUrlOrPath, toUserId, caption, token);
    }

    public String sendMedia(String toUserId, String mediaUrlOrPath, String caption) {
        String accountId = AccountContextResolver.resolveOutboundAccountId(accountStore.listAccountIds(), toUserId, contextTokenStore);
        return sendMedia(accountId, toUserId, mediaUrlOrPath, caption);
    }

    public void sendTyping(String accountId, String toUserId, boolean typing) {
        sessionGuard.assertActive(accountId);
        String contextToken = contextTokenStore.get(accountId, toUserId).orElse(null);
        String ticket = configCache.typingTicket(toUserId, contextToken);
        if (ticket == null || ticket.isBlank()) {
            return;
        }
        apiClient.sendTyping(toUserId, ticket, typing ? TypingStatus.TYPING : TypingStatus.CANCEL);
    }

    public void sendTyping(String toUserId, boolean typing) {
        String accountId = AccountContextResolver.resolveOutboundAccountId(accountStore.listAccountIds(), toUserId, contextTokenStore);
        sendTyping(accountId, toUserId, typing);
    }

    public WeixinLongPollMonitor createMonitor(String accountId, InboundMessageHandler handler) {
        InboundMessageHandler wrapper = (WeixinMessage message) -> {
            if (message.contextToken() != null && message.fromUserId() != null && !message.fromUserId().isBlank()) {
                contextTokenStore.put(accountId, message.fromUserId(), message.contextToken());
            }
            handler.onMessage(message);
        };
        return new WeixinLongPollMonitor(accountId, apiClient, syncCursorStore, contextTokenStore, sessionGuard, wrapper);
    }

    public WeixinLongPollMonitor createMonitorWithMedia(
        String accountId,
        InboundMessageHandler handler,
        InboundMessageEventHandler eventHandler
    ) {
        return createMonitorWithMedia(accountId, handler, eventHandler, null);
    }

    public WeixinLongPollMonitor createMonitorWithMedia(
        String accountId,
        InboundMessageHandler handler,
        InboundMessageEventHandler eventHandler,
        MonitorLogHandler logHandler
    ) {
        InboundMessageHandler wrapper = (WeixinMessage message) -> {
            if (message.contextToken() != null && message.fromUserId() != null && !message.fromUserId().isBlank()) {
                contextTokenStore.put(accountId, message.fromUserId(), message.contextToken());
            }
            handler.onMessage(message);
        };
        Path inboundDir = StateDirectoryResolver.resolveStateDir().resolve("openclaw-weixin").resolve("inbound-media");
        return new WeixinLongPollMonitor(
            accountId,
            apiClient,
            syncCursorStore,
            contextTokenStore,
            sessionGuard,
            wrapper,
            eventHandler,
            mediaDownloader,
            inboundDir,
            logHandler
        );
    }

    public WeixinMonitorStream monitorStream(String accountId) {
        return new WeixinMonitorStream(this, accountId);
    }
}
