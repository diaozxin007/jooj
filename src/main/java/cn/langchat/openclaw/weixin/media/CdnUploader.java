package cn.langchat.openclaw.weixin.media;

import cn.langchat.openclaw.weixin.api.WeixinApiException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class CdnUploader {
    private final HttpClient httpClient;
    private final int maxRetries;

    public CdnUploader() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), 3);
    }

    public CdnUploader(HttpClient httpClient, int maxRetries) {
        this.httpClient = httpClient;
        this.maxRetries = maxRetries;
    }

    public String upload(byte[] plaintext, byte[] aesKey, String uploadFullUrl, String uploadParam, String fileKey, String cdnBaseUrl) {
        byte[] ciphertext = AesEcb.encrypt(plaintext, aesKey);
        String url;
        if (uploadFullUrl != null && !uploadFullUrl.isBlank()) {
            url = uploadFullUrl;
        } else if (uploadParam != null && !uploadParam.isBlank()) {
            url = buildUploadUrl(cdnBaseUrl, uploadParam, fileKey);
        } else {
            throw new IllegalArgumentException("CDN upload URL missing");
        }

        Exception last = null;
        for (int i = 1; i <= maxRetries; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(ciphertext))
                    .build();
                HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());

                int status = resp.statusCode();
                if (status >= 400 && status < 500) {
                    throw new WeixinApiException("CDN client error " + status, status, new String(resp.body(), StandardCharsets.UTF_8));
                }
                if (status != 200) {
                    throw new IOException("CDN server error " + status);
                }
                String encryptedParam = resp.headers().firstValue("x-encrypted-param").orElse(null);
                if (encryptedParam == null || encryptedParam.isBlank()) {
                    throw new IOException("CDN response missing x-encrypted-param");
                }
                return encryptedParam;
            } catch (Exception ex) {
                last = ex;
                if (ex instanceof WeixinApiException) {
                    throw (WeixinApiException) ex;
                }
            }
        }

        throw new IllegalStateException("CDN upload failed", last);
    }

    private static String buildUploadUrl(String cdnBaseUrl, String uploadParam, String fileKey) {
        return CdnUrls.buildUploadUrl(cdnBaseUrl, uploadParam, fileKey);
    }
}
