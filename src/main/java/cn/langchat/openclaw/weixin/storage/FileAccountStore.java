package cn.langchat.openclaw.weixin.storage;

import cn.langchat.openclaw.weixin.model.WeixinAccount;
import cn.langchat.openclaw.weixin.util.Jsons;
import cn.langchat.openclaw.weixin.util.MapValues;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class FileAccountStore {
    private final Path stateDir;

    public FileAccountStore() {
        this(StateDirectoryResolver.resolveStateDir().resolve("openclaw-weixin"));
    }

    public FileAccountStore(Path stateDir) {
        this.stateDir = stateDir;
    }

    public void save(String accountId, String token, String baseUrl, String userId) {
        String canonical = canonicalAccountId(accountId);
        WeixinAccount account = new WeixinAccount(canonical, token, baseUrl, userId, Instant.now().toString());
        writeJson(accountPath(canonical), Jsons.toJson(account.toMap()));
        registerAccount(canonical);
    }

    public Optional<WeixinAccount> load(String accountId) {
        String raw = accountId == null ? "" : accountId.trim();
        if (raw.isBlank()) {
            return Optional.empty();
        }

        // 1) direct
        Optional<WeixinAccount> direct = loadFromPath(raw);
        if (direct.isPresent()) {
            return direct;
        }

        // 2) normalized fallback
        String normalized = AccountIdCompat.normalizeLikeTs(raw);
        if (!normalized.equals(raw)) {
            Optional<WeixinAccount> normalizedHit = loadFromPath(normalized);
            if (normalizedHit.isPresent()) {
                return normalizedHit;
            }
        }

        // 3) raw-id fallback from normalized pattern
        String derivedRaw = AccountIdCompat.deriveRawAccountId(raw);
        if (derivedRaw != null) {
            Optional<WeixinAccount> rawHit = loadFromPath(derivedRaw);
            if (rawHit.isPresent()) {
                return rawHit;
            }
        }
        if (derivedRaw == null) {
            String fromNormalized = AccountIdCompat.deriveRawAccountId(normalized);
            if (fromNormalized != null) {
                Optional<WeixinAccount> rawHit2 = loadFromPath(fromNormalized);
                if (rawHit2.isPresent()) {
                    return rawHit2;
                }
            }
        }

        // 4) very old single-account credentials fallback
        Optional<String> legacyToken = loadLegacySingleToken();
        return legacyToken.map(token -> new WeixinAccount(normalized, token, null, null, Instant.now().toString()));
    }

    public List<String> listAccountIds() {
        Path index = accountsIndexPath();
        if (Files.exists(index)) {
            try {
                Object root = Jsons.parse(Files.readString(index, StandardCharsets.UTF_8));
                if (root instanceof List<?> list) {
                    List<String> raw = new ArrayList<>(list.size());
                    for (Object it : list) {
                        if (it != null) {
                            String v = String.valueOf(it).trim();
                            if (!v.isBlank()) {
                                raw.add(v);
                            }
                        }
                    }
                    if (!raw.isEmpty()) {
                        List<String> canonical = canonicalizeAccountIds(raw);
                        if (!canonical.equals(raw)) {
                            writeJson(index, Jsons.toJson(canonical));
                        }
                        return List.copyOf(canonical);
                    }
                }
            } catch (Exception ignore) {
                // fallback to filesystem scan
            }
        }

        // fallback: scan account json files
        Path dir = accountsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            List<String> raw = stream
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(name -> name.endsWith(".json") && !name.endsWith(".sync.json") && !name.endsWith(".context-tokens.json"))
                .map(name -> name.substring(0, name.length() - 5))
                .toList();
            List<String> canonical = canonicalizeAccountIds(raw);
            if (!canonical.isEmpty()) {
                writeJson(index, Jsons.toJson(canonical));
            }
            return List.copyOf(canonical);
        } catch (IOException ex) {
            return List.of();
        }
    }

    public void registerAccount(String accountId) {
        String canonical = canonicalAccountId(accountId);
        List<String> existing = canonicalizeAccountIds(listAccountIds());
        if (!existing.contains(canonical)) {
            existing.add(canonical);
            writeJson(accountsIndexPath(), Jsons.toJson(existing));
        }
    }

    public void unregisterAccount(String accountId) {
        String canonical = canonicalAccountId(accountId);
        List<String> existing = canonicalizeAccountIds(listAccountIds());
        String raw = AccountIdCompat.deriveRawAccountId(canonical);
        boolean changed = existing.removeIf(id -> id.equals(canonical) || id.equals(accountId) || (raw != null && id.equals(raw)));
        if (changed) {
            writeJson(accountsIndexPath(), Jsons.toJson(existing));
        }
    }

    public void clearAccount(String accountId) {
        try {
            Files.deleteIfExists(accountPath(accountId));
            String canonical = canonicalAccountId(accountId);
            Files.deleteIfExists(accountPath(canonical));
            String raw = AccountIdCompat.deriveRawAccountId(canonical);
            if (raw != null) {
                Files.deleteIfExists(accountPath(raw));
            }
            Files.deleteIfExists(accountsDir().resolve(canonical + ".sync.json"));
            Files.deleteIfExists(accountsDir().resolve(canonical + ".context-tokens.json"));
        } catch (IOException ex) {
            throw new IllegalStateException("Clear account failed: " + accountId, ex);
        }
        unregisterAccount(accountId);
        unregisterAccount(canonicalAccountId(accountId));
    }

    private Optional<WeixinAccount> loadFromPath(String accountId) {
        Path path = accountPath(accountId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> m = Jsons.parseObject(Files.readString(path, StandardCharsets.UTF_8));
            return Optional.of(WeixinAccount.fromMap(accountId, m));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private Optional<String> loadLegacySingleToken() {
        Path legacy = StateDirectoryResolver.resolveStateDir().resolve("credentials").resolve("openclaw-weixin").resolve("credentials.json");
        if (!Files.exists(legacy)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> m = Jsons.parseObject(Files.readString(legacy, StandardCharsets.UTF_8));
            return MapValues.optionalString(m, "token");
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private Path accountsDir() {
        return stateDir.resolve("accounts");
    }

    private Path accountPath(String accountId) {
        return accountsDir().resolve(accountId + ".json");
    }

    private Path accountsIndexPath() {
        return stateDir.resolve("accounts.json");
    }

    private static String canonicalAccountId(String accountId) {
        return AccountIdCompat.normalizeLikeTs(accountId == null ? "" : accountId.trim());
    }

    private static List<String> canonicalizeAccountIds(List<String> ids) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String id : ids) {
            String canonical = canonicalAccountId(id);
            if (!canonical.isBlank()) {
                set.add(canonical);
            }
        }
        return new ArrayList<>(set);   // jooj fork:原 List.copyOf,改 ArrayList 让 registerAccount 能 add
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
