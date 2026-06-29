package cn.langchat.openclaw.weixin.api;

import cn.langchat.openclaw.weixin.model.GetConfigResponse;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class WeixinConfigCache {
    private static final long TTL_MS = Duration.ofHours(24).toMillis();
    private static final long INIT_RETRY_MS = 2_000L;
    private static final long MAX_RETRY_MS = Duration.ofHours(1).toMillis();

    private final WeixinApiClient apiClient;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public WeixinConfigCache(WeixinApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public String typingTicket(String userId, String contextToken) {
        long now = System.currentTimeMillis();
        Entry entry = cache.get(userId);
        boolean shouldFetch = entry == null || now >= entry.nextFetchAtMs;

        if (shouldFetch) {
            boolean ok = false;
            try {
                GetConfigResponse response = apiClient.getConfig(userId, contextToken);
                if (response.ret() != null && response.ret() == 0) {
                    long nextAt = now + (long) (Math.random() * TTL_MS);
                    cache.put(userId, new Entry(response.typingTicket() == null ? "" : response.typingTicket(), true, nextAt, INIT_RETRY_MS));
                    ok = true;
                }
            } catch (Exception ignore) {
                // ignore and fallback to cached value
            }
            if (!ok) {
                long previous = entry == null ? INIT_RETRY_MS : entry.retryDelayMs;
                long nextDelay = Math.min(previous * 2L, MAX_RETRY_MS);
                if (entry == null) {
                    cache.put(userId, new Entry("", false, now + INIT_RETRY_MS, INIT_RETRY_MS));
                } else {
                    cache.put(userId, new Entry(entry.typingTicket, entry.everSucceeded, now + nextDelay, nextDelay));
                }
            }
        }

        Entry latest = cache.get(userId);
        return latest == null ? "" : latest.typingTicket;
    }

    /**
     * @since 2026-04-20
     * @author LangChat Team
     */
    private record Entry(String typingTicket, boolean everSucceeded, long nextFetchAtMs, long retryDelayMs) {
    }
}
