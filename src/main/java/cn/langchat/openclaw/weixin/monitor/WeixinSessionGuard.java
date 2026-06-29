package cn.langchat.openclaw.weixin.monitor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class WeixinSessionGuard {
    public static final int SESSION_EXPIRED_ERRCODE = -14;

    private final long pauseDurationMs;
    private final Map<String, Long> pauseUntil = new ConcurrentHashMap<>();

    public WeixinSessionGuard() {
        this(Duration.ofHours(1));
    }

    public WeixinSessionGuard(Duration pauseDuration) {
        this.pauseDurationMs = pauseDuration.toMillis();
    }

    public void pause(String accountId) {
        pauseUntil.put(accountId, System.currentTimeMillis() + pauseDurationMs);
    }

    public boolean isPaused(String accountId) {
        Long until = pauseUntil.get(accountId);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            pauseUntil.remove(accountId);
            return false;
        }
        return true;
    }

    public long remainingPauseMs(String accountId) {
        Long until = pauseUntil.get(accountId);
        if (until == null) {
            return 0L;
        }
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0L) {
            pauseUntil.remove(accountId);
            return 0L;
        }
        return remaining;
    }

    public void assertActive(String accountId) {
        if (isPaused(accountId)) {
            long min = Math.max(1L, (long) Math.ceil(remainingPauseMs(accountId) / 60_000.0));
            throw new IllegalStateException("session paused for accountId=" + accountId + ", " + min + " min remaining");
        }
    }
}
