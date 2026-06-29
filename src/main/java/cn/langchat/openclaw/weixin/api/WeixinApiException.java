package cn.langchat.openclaw.weixin.api;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class WeixinApiException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;

    public WeixinApiException(String message) {
        super(message);
        this.statusCode = -1;
        this.responseBody = null;
    }

    public WeixinApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.responseBody = null;
    }

    public WeixinApiException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
