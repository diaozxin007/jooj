package cn.langchat.openclaw.weixin.auth;

import cn.langchat.openclaw.weixin.api.WeixinApiClient;
import cn.langchat.openclaw.weixin.api.WeixinApiException;
import cn.langchat.openclaw.weixin.api.WeixinApiPaths;
import cn.langchat.openclaw.weixin.api.WeixinClientConfig;
import cn.langchat.openclaw.weixin.util.MapValues;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class QrLoginClient {
    public static final String DEFAULT_BOT_TYPE = "3";

    private final WeixinApiClient apiClient;

    public QrLoginClient(WeixinApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public QrLoginStartResult start(String botType) {
        String type = (botType == null || botType.isBlank()) ? DEFAULT_BOT_TYPE : botType;
        String path = WeixinApiPaths.QR_START + "?bot_type=" + urlEncode(type);
        Map<String, Object> payload = apiClient.getJson(path, Duration.ofSeconds(15));
        return new QrLoginStartResult(
            MapValues.string(payload, "qrcode"),
            MapValues.string(payload, "qrcode_img_content")
        );
    }

    public QrLoginWaitResult pollStatus(String qrcode, Duration timeout) {
        String path = WeixinApiPaths.QR_STATUS + "?qrcode=" + urlEncode(qrcode);
        try {
            Map<String, Object> payload = apiClient.getJson(path, timeout == null ? Duration.ofSeconds(35) : timeout);
            return new QrLoginWaitResult(
                MapValues.string(payload, "status"),
                MapValues.string(payload, "bot_token"),
                MapValues.string(payload, "ilink_bot_id"),
                MapValues.string(payload, "baseurl"),
                MapValues.string(payload, "ilink_user_id"),
                MapValues.string(payload, "redirect_host")
            );
        } catch (WeixinApiException ex) {
            if (ex.getCause() instanceof java.net.http.HttpTimeoutException) {
                return new QrLoginWaitResult("wait", null, null, null, null, null);
            }
            throw ex;
        }
    }

    public QrLoginWaitResult pollStatus(String qrcode, Duration timeout, String baseUrlOverride) {
        if (baseUrlOverride == null || baseUrlOverride.isBlank()) {
            return pollStatus(qrcode, timeout);
        }
        WeixinClientConfig old = apiClient.config();
        WeixinClientConfig cfg = WeixinClientConfig.builder()
            .baseUrl(baseUrlOverride)
            .cdnBaseUrl(old.cdnBaseUrl())
            .token(old.token())
            .apiTimeout(old.apiTimeout())
            .longPollTimeout(old.longPollTimeout())
            .appId(old.appId())
            .channelVersion(old.channelVersion())
            .clientVersion(old.clientVersion())
            .routeTag(old.routeTag())
            .build();
        QrLoginClient client = new QrLoginClient(new WeixinApiClient(cfg));
        return client.pollStatus(qrcode, timeout);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
