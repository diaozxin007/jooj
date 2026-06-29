package cn.langchat.openclaw.weixin.media;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class CdnMediaDownloader {
    private final HttpClient httpClient;
    private final String cdnBaseUrl;
    private final boolean enableFallback;

    public CdnMediaDownloader(String cdnBaseUrl) {
        this(cdnBaseUrl, true);
    }

    public CdnMediaDownloader(String cdnBaseUrl, boolean enableFallback) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.cdnBaseUrl = cdnBaseUrl;
        this.enableFallback = enableFallback;
    }

    public byte[] downloadAndDecrypt(String encryptedQueryParam, String aesKeyBase64, String fullUrl) {
        byte[] key = parseAesKey(aesKeyBase64);
        byte[] encrypted = downloadPlain(encryptedQueryParam, fullUrl);
        return AesEcb.decrypt(encrypted, key);
    }

    public byte[] downloadPlain(String encryptedQueryParam, String fullUrl) {
        String url;
        if (fullUrl != null && !fullUrl.isBlank()) {
            url = fullUrl;
        } else if (enableFallback && encryptedQueryParam != null && !encryptedQueryParam.isBlank()) {
            url = CdnUrls.buildDownloadUrl(cdnBaseUrl, encryptedQueryParam);
        } else {
            throw new IllegalArgumentException("fullUrl is required when CDN URL fallback is disabled");
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("CDN download failed status=" + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("CDN download failed", ex);
        }
    }

    public Path downloadAndDecryptToFile(String encryptedQueryParam, String aesKeyBase64, String fullUrl, Path targetFile) {
        byte[] plain = downloadAndDecrypt(encryptedQueryParam, aesKeyBase64, fullUrl);
        try {
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile, plain);
            return targetFile;
        } catch (IOException ex) {
            throw new IllegalStateException("Write file failed: " + targetFile, ex);
        }
    }

    private static byte[] parseAesKey(String aesKeyBase64) {
        byte[] decoded = Base64.getDecoder().decode(aesKeyBase64);
        if (decoded.length == 16) {
            return decoded;
        }
        if (decoded.length == 32) {
            String text = new String(decoded, java.nio.charset.StandardCharsets.US_ASCII);
            if (text.matches("[0-9a-fA-F]{32}")) {
                return java.util.HexFormat.of().parseHex(text);
            }
        }
        throw new IllegalArgumentException("Unsupported aes_key format");
    }
}
