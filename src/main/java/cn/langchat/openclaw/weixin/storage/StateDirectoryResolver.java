package cn.langchat.openclaw.weixin.storage;

import java.nio.file.Path;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class StateDirectoryResolver {
    private StateDirectoryResolver() {
    }

    public static Path resolveStateDir() {
        String a = System.getenv("OPENCLAW_STATE_DIR");
        if (a != null && !a.isBlank()) {
            return Path.of(a);
        }
        String b = System.getenv("CLAWDBOT_STATE_DIR");
        if (b != null && !b.isBlank()) {
            return Path.of(b);
        }
        return Path.of(System.getProperty("user.home"), ".openclaw");
    }
}
