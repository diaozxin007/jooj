package cn.langchat.openclaw.weixin.media;

import cn.langchat.openclaw.weixin.api.WeixinApiClient;
import cn.langchat.openclaw.weixin.model.GetUploadUrlRequest;
import cn.langchat.openclaw.weixin.model.GetUploadUrlResponse;
import cn.langchat.openclaw.weixin.model.UploadMediaType;
import cn.langchat.openclaw.weixin.model.UploadedMedia;
import cn.langchat.openclaw.weixin.util.Hashing;
import cn.langchat.openclaw.weixin.util.IdGenerator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class MediaUploadService {
    private final WeixinApiClient apiClient;
    private final CdnUploader cdnUploader;
    private final HttpClient httpClient;

    public MediaUploadService(WeixinApiClient apiClient) {
        this(apiClient, new CdnUploader(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public MediaUploadService(WeixinApiClient apiClient, CdnUploader cdnUploader) {
        this(apiClient, cdnUploader, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public MediaUploadService(WeixinApiClient apiClient, CdnUploader cdnUploader, HttpClient httpClient) {
        this.apiClient = apiClient;
        this.cdnUploader = cdnUploader;
        this.httpClient = httpClient;
    }

    public UploadedMedia uploadImage(Path file, String toUserId) {
        return upload(file, toUserId, UploadMediaType.IMAGE);
    }

    public UploadedMedia uploadVideo(Path file, String toUserId) {
        return upload(file, toUserId, UploadMediaType.VIDEO);
    }

    public UploadedMedia uploadFile(Path file, String toUserId) {
        return upload(file, toUserId, UploadMediaType.FILE);
    }

    private UploadedMedia upload(Path file, String toUserId, UploadMediaType mediaType) {
        byte[] plain;
        try {
            plain = Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new IllegalStateException("Read file failed: " + file, ex);
        }

        String fileKey = IdGenerator.randomHex(16);
        byte[] aesKey = java.util.HexFormat.of().parseHex(IdGenerator.randomHex(16));
        String aesKeyHex = java.util.HexFormat.of().formatHex(aesKey);

        GetUploadUrlRequest req = new GetUploadUrlRequest(
            fileKey,
            mediaType,
            toUserId,
            plain.length,
            Hashing.md5Hex(plain),
            AesEcb.paddedSize(plain.length),
            null,
            null,
            null,
            Boolean.TRUE,
            aesKeyHex
        );

        GetUploadUrlResponse uploadUrl = apiClient.getUploadUrl(req);
        String encryptedQueryParam = cdnUploader.upload(
            plain,
            aesKey,
            uploadUrl.uploadFullUrl(),
            uploadUrl.uploadParam(),
            fileKey,
            apiClient.config().cdnBaseUrl()
        );

        return new UploadedMedia(
            fileKey,
            encryptedQueryParam,
            aesKeyHex,
            plain.length,
            AesEcb.paddedSize(plain.length)
        );
    }

    public String sendMedia(Path file, String toUserId, String text, String contextToken) {
        String mime = ContentTypes.fromFilename(file);
        UploadedMedia uploaded;

        if (mime.startsWith("image/")) {
            uploaded = uploadImage(file, toUserId);
            return apiClient.sendMediaMessage(toUserId, text, contextToken, buildImageItem(uploaded));
        }
        if (mime.startsWith("video/")) {
            uploaded = uploadVideo(file, toUserId);
            return apiClient.sendMediaMessage(toUserId, text, contextToken, buildVideoItem(uploaded));
        }
        uploaded = uploadFile(file, toUserId);
        return apiClient.sendMediaMessage(toUserId, text, contextToken, buildFileItem(file.getFileName().toString(), uploaded));
    }

    public String sendMedia(String mediaUrlOrPath, String toUserId, String text, String contextToken) {
        Path localFile = resolveToLocalFile(mediaUrlOrPath);
        return sendMedia(localFile, toUserId, text, contextToken);
    }

    private Path resolveToLocalFile(String mediaUrlOrPath) {
        if (mediaUrlOrPath == null || mediaUrlOrPath.isBlank()) {
            throw new IllegalArgumentException("mediaUrlOrPath is blank");
        }
        if (mediaUrlOrPath.startsWith("file://")) {
            return Path.of(URI.create(mediaUrlOrPath));
        }
        if (mediaUrlOrPath.startsWith("http://") || mediaUrlOrPath.startsWith("https://")) {
            return downloadRemoteToTemp(mediaUrlOrPath);
        }
        return Path.of(mediaUrlOrPath).toAbsolutePath();
    }

    private Path downloadRemoteToTemp(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("remote media download failed: " + resp.statusCode());
            }
            String ext = extensionFromUrl(url);
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "openclaw-weixin", "outbound-temp");
            Files.createDirectories(dir);
            Path target = dir.resolve("weixin-remote-" + System.currentTimeMillis() + "-" + IdGenerator.randomHex(4) + ext);
            Files.write(target, resp.body());
            return target;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("remote media download failed: " + url, ex);
        }
    }

    private static String extensionFromUrl(String url) {
        int q = url.indexOf('?');
        String clean = q >= 0 ? url.substring(0, q) : url;
        int slash = clean.lastIndexOf('/');
        String name = slash >= 0 ? clean.substring(slash + 1) : clean;
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot);
            if (ext.length() <= 8) {
                return ext;
            }
        }
        return ".bin";
    }

    private static java.util.Map<String, Object> buildImageItem(UploadedMedia media) {
        return java.util.Map.of(
            "type", 2,
            "image_item", java.util.Map.of(
                "media", java.util.Map.of(
                    "encrypt_query_param", media.downloadEncryptedQueryParam(),
                    "aes_key", java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(media.aesKeyHex())),
                    "encrypt_type", 1
                ),
                "mid_size", media.cipherSize()
            )
        );
    }

    private static java.util.Map<String, Object> buildVideoItem(UploadedMedia media) {
        return java.util.Map.of(
            "type", 5,
            "video_item", java.util.Map.of(
                "media", java.util.Map.of(
                    "encrypt_query_param", media.downloadEncryptedQueryParam(),
                    "aes_key", java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(media.aesKeyHex())),
                    "encrypt_type", 1
                ),
                "video_size", media.cipherSize()
            )
        );
    }

    private static java.util.Map<String, Object> buildFileItem(String filename, UploadedMedia media) {
        return java.util.Map.of(
            "type", 4,
            "file_item", java.util.Map.of(
                "media", java.util.Map.of(
                    "encrypt_query_param", media.downloadEncryptedQueryParam(),
                    "aes_key", java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(media.aesKeyHex())),
                    "encrypt_type", 1
                ),
                "file_name", filename,
                "len", Long.toString(media.plainSize())
            )
        );
    }
}
