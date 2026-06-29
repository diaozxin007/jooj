package cn.langchat.openclaw.weixin.storage;

import cn.langchat.openclaw.weixin.util.Jsons;
import cn.langchat.openclaw.weixin.util.MapValues;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class FileSyncCursorStore {
    private final Path accountDir;

    public FileSyncCursorStore() {
        this(StateDirectoryResolver.resolveStateDir().resolve("openclaw-weixin").resolve("accounts"));
    }

    public FileSyncCursorStore(Path accountDir) {
        this.accountDir = accountDir;
    }

    public Optional<String> load(String accountId) {
        String normalized = AccountIdCompat.normalizeLikeTs(accountId);

        // 1) primary normalized path
        Optional<String> primary = read(syncPath(normalized));
        if (primary.isPresent()) {
            return primary;
        }

        // 2) compat raw path
        String raw = AccountIdCompat.deriveRawAccountId(normalized);
        if (raw != null) {
            Optional<String> compatRaw = read(syncPath(raw));
            if (compatRaw.isPresent()) {
                return compatRaw;
            }
        }

        // 3) very old legacy path
        Path legacy = StateDirectoryResolver.resolveStateDir()
            .resolve("agents")
            .resolve("default")
            .resolve("sessions")
            .resolve(".openclaw-weixin-sync")
            .resolve("default.json");
        return read(legacy);
    }

    public void save(String accountId, String getUpdatesBuf) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("get_updates_buf", getUpdatesBuf == null ? "" : getUpdatesBuf);
        writeJson(syncPath(AccountIdCompat.normalizeLikeTs(accountId)), Jsons.toJson(map));
    }

    private Path syncPath(String accountId) {
        return accountDir.resolve(accountId + ".sync.json");
    }

    private Optional<String> read(Path path) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, Object> map = Jsons.parseObject(raw);
            return MapValues.optionalString(map, "get_updates_buf");
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static void writeJson(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Write file failed: " + file, ex);
        }
    }
}
