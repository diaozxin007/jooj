package cn.langchat.openclaw.weixin.media;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class CdnUrls {
    private CdnUrls() {
    }

    public static String buildDownloadUrl(String cdnBaseUrl, String encryptedQueryParam) {
        return cdnBaseUrl + "/download?encrypted_query_param=" + URLEncoder.encode(encryptedQueryParam, StandardCharsets.UTF_8);
    }

    public static String buildUploadUrl(String cdnBaseUrl, String uploadParam, String fileKey) {
        return cdnBaseUrl
            + "/upload?encrypted_query_param=" + URLEncoder.encode(uploadParam, StandardCharsets.UTF_8)
            + "&filekey=" + URLEncoder.encode(fileKey, StandardCharsets.UTF_8);
    }
}
