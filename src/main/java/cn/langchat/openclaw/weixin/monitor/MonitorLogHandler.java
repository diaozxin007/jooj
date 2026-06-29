package cn.langchat.openclaw.weixin.monitor;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
@FunctionalInterface
public interface MonitorLogHandler {
    void onLog(String level, String message);
}
