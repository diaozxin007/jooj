package cn.langchat.openclaw.weixin.api;

import cn.langchat.openclaw.weixin.model.BaseInfo;
import cn.langchat.openclaw.weixin.model.GetConfigResponse;
import cn.langchat.openclaw.weixin.model.GetUpdatesResponse;
import cn.langchat.openclaw.weixin.model.GetUploadUrlRequest;
import cn.langchat.openclaw.weixin.model.GetUploadUrlResponse;
import cn.langchat.openclaw.weixin.model.TypingStatus;
import cn.langchat.openclaw.weixin.util.IdGenerator;
import cn.langchat.openclaw.weixin.util.Jsons;
import cn.langchat.openclaw.weixin.util.Redaction;
import cn.langchat.openclaw.weixin.util.StreamingMarkdownFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class WeixinApiClient {
    private static final boolean DEBUG = Boolean.getBoolean("openclaw.weixin.debug");
    /** SLF4J logger —— 失败响应不靠 -Dopenclaw.weixin.debug 开关,永远输出。
     *  jooj 改造:接腾讯 server 时撞 errcode=-14 之类必须看到,不能藏在 println 里。 */
    private static final Logger LOG = LoggerFactory.getLogger(WeixinApiClient.class);

    private final WeixinClientConfig config;
    private final HttpClient httpClient;

    /**
     * jooj 改造(Demo 16.8):token 动态供给。
     *
     * <p>原 SDK 设计 token 写在 {@code WeixinClientConfig} 里,SDK 构造时定死 ——
     * 不适合 jooj 这种"启动时还没扫码 → 扫码后才有 token"流程。加个 supplier 让
     * apiClient 每次请求**实时**取 token,扫码后 token 立刻生效不用重建 SDK。
     *
     * <p>null 时 fallback 到 {@code config.token()}(保留原行为给 OpenClaw gateway 等
     * 上层场景用)。
     */
    private volatile java.util.function.Supplier<String> tokenSupplier;

    public WeixinApiClient(WeixinClientConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public WeixinApiClient(WeixinClientConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    public WeixinClientConfig config() {
        return config;
    }

    /**
     * jooj 改造(Demo 16.8):设 token supplier,扫码后动态生效。
     * 调用方:{@link com.xilidou.jooj.weixin.WeixinChannel} 启动 monitor 前调一次。
     */
    public void setTokenSupplier(java.util.function.Supplier<String> supplier) {
        this.tokenSupplier = supplier;
    }

    public GetUpdatesResponse getUpdates(String getUpdatesBuf, Duration timeout) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("get_updates_buf", getUpdatesBuf == null ? "" : getUpdatesBuf);
        body.put("base_info", buildBaseInfo().toMap());

        Duration effectiveTimeout = timeout == null ? config.longPollTimeout() : timeout;
        try {
            Map<String, Object> parsed = postJson(WeixinApiPaths.GET_UPDATES, body, effectiveTimeout, true);
            return GetUpdatesResponse.fromMap(parsed);
        } catch (WeixinApiException ex) {
            if (ex.getCause() instanceof HttpTimeoutException) {
                debug("getUpdates timeout -> empty response");
                return new GetUpdatesResponse(0, null, null, List.of(), getUpdatesBuf, null, Map.of());
            }
            throw ex;
        }
    }

    public String sendTextMessage(String toUserId, String text, String contextToken) {
        String clientId = IdGenerator.clientId("openclaw-weixin");
        String filteredText = filterMarkdown(text);

        List<Object> items = new ArrayList<>();
        if (filteredText != null && !filteredText.isEmpty()) {
            items.add(Map.of(
                "type", 1,
                "text_item", Map.of("text", filteredText)
            ));
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("from_user_id", "");
        msg.put("to_user_id", toUserId);
        msg.put("client_id", clientId);
        msg.put("message_type", 2);
        msg.put("message_state", 2);
        if (!items.isEmpty()) {
            msg.put("item_list", items);
        }
        if (contextToken != null && !contextToken.isBlank()) {
            msg.put("context_token", contextToken);
        }

        sendMessage(Map.of("msg", msg));
        return clientId;
    }

    public List<String> sendTextStream(String toUserId, Iterable<String> chunks, String contextToken) {
        return sendTextStream(toUserId, chunks, contextToken, null);
    }

    public List<String> sendTextStream(String toUserId, Iterable<String> chunks, String contextToken, Duration interval) {
        List<String> messageIds = new ArrayList<>();
        if (chunks == null) {
            return List.of();
        }
        long sleepMs = interval == null ? 0L : Math.max(0L, interval.toMillis());

        for (String chunk : chunks) {
            if (chunk == null || chunk.isEmpty()) {
                continue;
            }
            String filtered = StreamingMarkdownFilter.sanitize(chunk);
            if (filtered.isBlank()) {
                continue;
            }
            messageIds.add(sendTextMessage(toUserId, filtered, contextToken));
            if (sleepMs > 0L) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return List.copyOf(messageIds);
    }

    public String sendMediaMessage(String toUserId, String text, String contextToken, Map<String, Object> mediaItem) {
        String filteredText = filterMarkdown(text);
        if (filteredText != null && !filteredText.isBlank()) {
            sendTextMessage(toUserId, filteredText, contextToken);
        }
        String clientId = IdGenerator.clientId("openclaw-weixin");
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("from_user_id", "");
        msg.put("to_user_id", toUserId);
        msg.put("client_id", clientId);
        msg.put("message_type", 2);
        msg.put("message_state", 2);
        msg.put("item_list", List.of(mediaItem));
        if (contextToken != null && !contextToken.isBlank()) {
            msg.put("context_token", contextToken);
        }
        sendMessage(Map.of("msg", msg));
        return clientId;
    }

    public void sendMessage(Map<String, Object> requestBody) {
        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        body.put("base_info", buildBaseInfo().toMap());
        postJson(WeixinApiPaths.SEND_MESSAGE, body, config.apiTimeout(), true);
    }

    public GetUploadUrlResponse getUploadUrl(GetUploadUrlRequest request) {
        Map<String, Object> body = new LinkedHashMap<>(request.toMap());
        body.put("base_info", buildBaseInfo().toMap());
        Map<String, Object> parsed = postJson(WeixinApiPaths.GET_UPLOAD_URL, body, config.apiTimeout(), true);
        return GetUploadUrlResponse.fromMap(parsed);
    }

    public GetConfigResponse getConfig(String ilinkUserId, String contextToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ilink_user_id", ilinkUserId);
        if (contextToken != null && !contextToken.isBlank()) {
            body.put("context_token", contextToken);
        }
        body.put("base_info", buildBaseInfo().toMap());
        Map<String, Object> parsed = postJson(WeixinApiPaths.GET_CONFIG, body, config.apiTimeout(), true);
        return GetConfigResponse.fromMap(parsed);
    }

    public void sendTyping(String ilinkUserId, String typingTicket, TypingStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ilink_user_id", ilinkUserId);
        body.put("typing_ticket", typingTicket);
        body.put("status", status.code());
        body.put("base_info", buildBaseInfo().toMap());
        postJson(WeixinApiPaths.SEND_TYPING, body, config.apiTimeout(), true);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getJson(String pathWithQuery, Duration timeout) {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + pathWithQuery))
            .GET()
            .timeout(timeout == null ? config.apiTimeout() : timeout);
        buildHeaders(false, null).forEach(reqBuilder::header);

        debug("GET " + Redaction.redactUrl(config.baseUrl() + pathWithQuery));

        try {
            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            debug("GET status=" + resp.statusCode() + " body=" + Redaction.redactJson(resp.body()));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new WeixinApiException("GET " + pathWithQuery + " failed", resp.statusCode(), resp.body());
            }
            Object parsed = Jsons.parse(resp.body());
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new WeixinApiException("GET " + pathWithQuery + " response is not JSON object");
            }
            return (Map<String, Object>) map;
        } catch (HttpTimeoutException timeoutEx) {
            throw new WeixinApiException("GET " + pathWithQuery + " timeout", timeoutEx);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new WeixinApiException("GET " + pathWithQuery + " failed", ex);
        }
    }

    private BaseInfo buildBaseInfo() {
        return new BaseInfo(config.channelVersion());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(String path, Map<String, Object> body, Duration timeout, boolean withAuth) {
        String payload = Jsons.toJson(body);
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + path))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .timeout(timeout == null ? config.apiTimeout() : timeout);

        Map<String, String> headers = buildHeaders(withAuth, bytes);
        headers.forEach(reqBuilder::header);

        // jooj 改造:永久 log,不靠 -Dopenclaw.weixin.debug 开关。
        // 关键诊断信息:Authorization 头是不是空、body 是什么、server 返什么。
        String authHeader = headers.getOrDefault("Authorization", "<missing>");
        String authPreview = authHeader.length() > 20 ? authHeader.substring(0, 20) + "..." : authHeader;
        LOG.info("[weixin-http] POST {} auth={} bodyLen={}", path, authPreview, bytes.length);
        debug("POST " + Redaction.redactUrl(config.baseUrl() + path) + " body=" + Redaction.redactJson(payload));

        try {
            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // jooj 改造:永久 log status + body(body trim 到 300 char 防刷屏)。
            String respBody = resp.body() == null ? "" : resp.body();
            String respPreview = respBody.length() > 300 ? respBody.substring(0, 300) + "..." : respBody;
            LOG.info("[weixin-http] POST {} → status={} body={}", path, resp.statusCode(), respPreview);
            debug("POST status=" + resp.statusCode() + " body=" + Redaction.redactJson(resp.body()));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new WeixinApiException("POST " + path + " failed", resp.statusCode(), resp.body());
            }
            String bodyText = respBody.trim();
            if (bodyText.isEmpty()) {
                return Map.of();
            }
            Object parsed = Jsons.parse(bodyText);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new WeixinApiException("POST " + path + " response is not JSON object");
            }
            return (Map<String, Object>) map;
        } catch (HttpTimeoutException timeoutEx) {
            throw new WeixinApiException("POST " + path + " timeout", timeoutEx);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new WeixinApiException("POST " + path + " failed", ex);
        }
    }

    private Map<String, String> buildHeaders(boolean withAuth, byte[] bodyBytes) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("iLink-App-Id", config.appId());
        headers.put("iLink-App-ClientVersion", Integer.toString(config.clientVersion()));
        if (config.routeTag() != null && !config.routeTag().isBlank()) {
            headers.put("SKRouteTag", config.routeTag());
        }

        if (bodyBytes != null) {
            headers.put("Content-Type", "application/json");
            headers.put("AuthorizationType", "ilink_bot_token");
            headers.put("X-WECHAT-UIN", IdGenerator.randomWechatUinHeader());
        }

        if (withAuth) {
            // jooj 改造(Demo 16.8):优先用 tokenSupplier(动态),fallback config.token(原行为)。
            String token = null;
            if (tokenSupplier != null) {
                try {
                    token = tokenSupplier.get();
                } catch (Exception ex) {
                    LOG.warn("[weixin-http] tokenSupplier threw, falling back to config.token: {}", ex.toString());
                }
            }
            if (token == null || token.isBlank()) {
                token = config.token();
            }
            if (token != null && !token.isBlank()) {
                headers.put("Authorization", "Bearer " + token.trim());
            }
        }

        return headers;
    }

    private static String filterMarkdown(String text) {
        StreamingMarkdownFilter f = new StreamingMarkdownFilter();
        f.feed(text == null ? "" : text);
        return f.flush();
    }

    private static void debug(String message) {
        if (DEBUG) {
            System.out.println("[weixin-sdk][debug] " + message);
        }
    }
}
