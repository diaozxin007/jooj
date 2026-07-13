package com.xilidou.jooj.agent.control;

/**
 * 用户"拒绝"型答复,或超时/中断兜底的 deny。
 *
 * @param reason  原因(前端"取消"= "user cancelled"、超时 = "timeout after 3m"、
 *                interrupt = "interrupted by user")
 */
public record DenyAnswer(String reason) implements Answer {

    /** 超时兜底。 */
    public static DenyAnswer timeout() {
        return new DenyAnswer("user did not respond within timeout");
    }

    /** 用户在挂起期间点了 stop,ask 被 cancel 掉时的兜底。 */
    public static DenyAnswer interrupted() {
        return new DenyAnswer("interrupted by user");
    }

    /** 用户主动点"拒绝"按钮。 */
    public static DenyAnswer userRejected() {
        return new DenyAnswer("user rejected");
    }
}
