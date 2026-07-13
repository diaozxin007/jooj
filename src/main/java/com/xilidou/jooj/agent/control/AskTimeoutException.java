package com.xilidou.jooj.agent.control;

/**
 * s22 D-10-B:{@code AgentControl.ask} 超时抛出。
 *
 * <p>调用方(PermissionHook)catch 后按 tool 侧约定处理:permission 场景转 DenyAnswer.timeout()
 * 走 DENY 路径。
 */
public class AskTimeoutException extends RuntimeException {

    private final String askId;

    public AskTimeoutException(String askId) {
        super("Ask timed out (askId=" + askId + ")");
        this.askId = askId;
    }

    public String getAskId() {
        return askId;
    }
}
