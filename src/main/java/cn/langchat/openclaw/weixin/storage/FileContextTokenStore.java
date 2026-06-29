package cn.langchat.openclaw.weixin.storage;

import cn.langchat.openclaw.weixin.util.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class FileContextTokenStore {
    private final Path accountDir;
    private final ConcurrentHashMap<String, String> memory = new ConcurrentHashMap<>();

    public FileContextTokenStore() {
        this(StateDirectoryResolver.resolveStateDir().resolve("openclaw-weixin").resolve("accounts"));
    }

    public FileContextTokenStore(Path accountDir) {
        this.accountDir = accountDir;
    }

    public void restore(String accountId) {
        String normalized = AccountIdCompat.normalizeLikeTs(accountId);

        if (restoreOne(normalized)) {
            return;
        }
        String raw = AccountIdCompat.deriveRawAccountId(normalized);
        if (raw != null) {
            restoreOne(raw);
        }
    }

    public void put(String accountId, String userId, String contextToken) {
        String normalized = AccountIdCompat.normalizeLikeTs(accountId);
        memory.put(key(normalized, userId), contextToken);
        persist(normalized);
    }

    public Optional<String> get(String accountId, String userId) {
        String normalized = AccountIdCompat.normalizeLikeTs(accountId);
        String v = memory.get(key(normalized, userId));
        if (v != null) {
            return Optional.of(v);
        }
        String raw = AccountIdCompat.deriveRawAccountId(normalized);
        if (raw != null) {
            return Optional.ofNullable(memory.get(key(raw, userId)));
        }
        return Optional.empty();
    }

    public Set<String> listUserIds(String accountId) {
        String normalized = AccountIdCompat.normalizeLikeTs(accountId);
        restore(normalized);
        Set<String> out = new TreeSet<>();

        String p1 = normalized + ":";
        memory.forEach((k, v) -> {
            if (k.startsWith(p1)) {
                out.add(k.substring(p1.length()));
            }
        });

        String raw = AccountIdCompat.deriveRawAccountId(normalized);
        if (raw != null) {
            String p2 = raw + ":";
            memory.forEach((k, v) -> {
                if (k.startsWith(p2)) {
                    out.add(k.substring(p2.length()));
                }
            });
        }
        return Set.copyOf(out);
    }

    public void clearAccount(String accountId) {
        String normalized = AccountIdCompat.normalizeLikeTs(accountId);
        clearPrefix(normalized + ":");
        String raw = AccountIdCompat.deriveRawAccountId(normalized);
        if (raw != null) {
            clearPrefix(raw + ":");
        }
        try {
            Files.deleteIfExists(tokenPath(normalized));
            if (raw != null) {
                Files.deleteIfExists(tokenPath(raw));
            }
        } catch (IOException ignore) {
            // ignore
        }
    }

    public boolean hasContextToken(String accountId, String userId) {
        return get(accountId, userId).isPresent();
    }

    private boolean restoreOne(String accountId) {
        Path path = tokenPath(accountId);
        if (!Files.exists(path)) {
            return false;
        }
        try {
            Map<String, Object> parsed = Jsons.parseObject(Files.readString(path, StandardCharsets.UTF_8));
            parsed.forEach((userId, value) -> {
                if (value != null) {
                    memory.put(key(accountId, userId), String.valueOf(value));
                }
            });
            return true;
        } catch (Exception ignore) {
            return false;
        }
    }

    private void persist(String accountId) {
        String prefix = accountId + ":";
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        memory.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                output.put(k.substring(prefix.length()), v);
            }
        });
        Path path = tokenPath(accountId);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, Jsons.toJson(output), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Persist context token failed: " + path, ex);
        }
    }

    private void clearPrefix(String prefix) {
        memory.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private Path tokenPath(String accountId) {
        return accountDir.resolve(accountId + ".context-tokens.json");
    }

    private static String key(String accountId, String userId) {
        return accountId + ":" + userId;
    }
}
