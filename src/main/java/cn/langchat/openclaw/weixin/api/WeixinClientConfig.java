package cn.langchat.openclaw.weixin.api;

import java.time.Duration;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class WeixinClientConfig {
    public static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";
    public static final String DEFAULT_CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c";
    public static final String DEFAULT_APP_ID = "bot";
    public static final String DEFAULT_CHANNEL_VERSION = "0.1.0";

    private final String baseUrl;
    private final String cdnBaseUrl;
    private final String token;
    private final Duration apiTimeout;
    private final Duration longPollTimeout;
    private final String appId;
    private final String channelVersion;
    private final int clientVersion;
    private final String routeTag;

    private WeixinClientConfig(Builder builder) {
        this.baseUrl = normalizeBaseUrl(builder.baseUrl);
        this.cdnBaseUrl = normalizeBaseUrl(builder.cdnBaseUrl);
        this.token = builder.token;
        this.apiTimeout = builder.apiTimeout;
        this.longPollTimeout = builder.longPollTimeout;
        this.appId = builder.appId;
        this.channelVersion = builder.channelVersion;
        this.clientVersion = builder.clientVersion;
        this.routeTag = builder.routeTag;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String cdnBaseUrl() {
        return cdnBaseUrl;
    }

    public String token() {
        return token;
    }

    public Duration apiTimeout() {
        return apiTimeout;
    }

    public Duration longPollTimeout() {
        return longPollTimeout;
    }

    public String appId() {
        return appId;
    }

    public String channelVersion() {
        return channelVersion;
    }

    public int clientVersion() {
        return clientVersion;
    }

    public String routeTag() {
        return routeTag;
    }

    public static int toClientVersion(String semver) {
        String[] parts = semver == null ? new String[0] : semver.split("\\.");
        int major = parts.length > 0 ? parseInt(parts[0]) : 0;
        int minor = parts.length > 1 ? parseInt(parts[1]) : 0;
        int patch = parts.length > 2 ? parseInt(parts[2]) : 0;
        return ((major & 0xff) << 16) | ((minor & 0xff) << 8) | (patch & 0xff);
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        if (raw.endsWith("/")) {
            return raw.substring(0, raw.length() - 1);
        }
        return raw;
    }

    /**
     * @since 2026-04-20
     * @author LangChat Team
     */
    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String cdnBaseUrl = DEFAULT_CDN_BASE_URL;
        private String token;
        private Duration apiTimeout = Duration.ofSeconds(15);
        private Duration longPollTimeout = Duration.ofSeconds(35);
        private String appId = DEFAULT_APP_ID;
        private String channelVersion = DEFAULT_CHANNEL_VERSION;
        private int clientVersion = toClientVersion(DEFAULT_CHANNEL_VERSION);
        private String routeTag;

        private Builder() {
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder cdnBaseUrl(String cdnBaseUrl) {
            this.cdnBaseUrl = cdnBaseUrl;
            return this;
        }

        public Builder token(String token) {
            this.token = token;
            return this;
        }

        public Builder apiTimeout(Duration apiTimeout) {
            this.apiTimeout = apiTimeout;
            return this;
        }

        public Builder longPollTimeout(Duration longPollTimeout) {
            this.longPollTimeout = longPollTimeout;
            return this;
        }

        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        public Builder channelVersion(String channelVersion) {
            this.channelVersion = channelVersion;
            this.clientVersion = toClientVersion(channelVersion);
            return this;
        }

        public Builder clientVersion(int clientVersion) {
            this.clientVersion = clientVersion;
            return this;
        }

        public Builder routeTag(String routeTag) {
            this.routeTag = routeTag;
            return this;
        }

        public WeixinClientConfig build() {
            return new WeixinClientConfig(this);
        }
    }
}
