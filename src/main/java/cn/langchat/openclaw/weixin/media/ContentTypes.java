package cn.langchat.openclaw.weixin.media;

import java.nio.file.Path;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class ContentTypes {
    private ContentTypes() {
    }

    public static String fromFilename(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (name.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (name.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (name.endsWith(".avi")) {
            return "video/x-msvideo";
        }
        if (name.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (name.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (name.endsWith(".txt")) {
            return "text/plain";
        }
        if (name.endsWith(".json")) {
            return "application/json";
        }
        if (name.endsWith(".doc")) {
            return "application/msword";
        }
        if (name.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (name.endsWith(".xls")) {
            return "application/vnd.ms-excel";
        }
        if (name.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (name.endsWith(".zip")) {
            return "application/zip";
        }
        return "application/octet-stream";
    }
}
