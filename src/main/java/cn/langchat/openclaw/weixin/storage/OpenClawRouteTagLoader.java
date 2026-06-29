package cn.langchat.openclaw.weixin.storage;

import cn.langchat.openclaw.weixin.util.Jsons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class OpenClawRouteTagLoader {
    private OpenClawRouteTagLoader() {
    }

    public static Optional<String> loadRouteTag(Path openclawConfigPath, String accountId) {
        if (openclawConfigPath == null || !Files.exists(openclawConfigPath)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> root = Jsons.parseObject(Files.readString(openclawConfigPath, StandardCharsets.UTF_8));
            Object channelsObj = root.get("channels");
            if (!(channelsObj instanceof Map<?, ?> channelsAny)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> channels = (Map<String, Object>) channelsAny;

            Object sectionObj = channels.get("openclaw-weixin");
            if (!(sectionObj instanceof Map<?, ?> sectionAny)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> section = (Map<String, Object>) sectionAny;

            if (accountId != null && !accountId.isBlank()) {
                Object accountsObj = section.get("accounts");
                if (accountsObj instanceof Map<?, ?> accountsAny) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> accounts = (Map<String, Object>) accountsAny;
                    Object accountObj = accounts.get(accountId);
                    if (accountObj instanceof Map<?, ?> accountAny) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> account = (Map<String, Object>) accountAny;
                        Object tag = account.get("routeTag");
                        if (tag != null && !String.valueOf(tag).isBlank()) {
                            return Optional.of(String.valueOf(tag).trim());
                        }
                    }
                }
            }

            Object sectionTag = section.get("routeTag");
            if (sectionTag != null && !String.valueOf(sectionTag).isBlank()) {
                return Optional.of(String.valueOf(sectionTag).trim());
            }
            return Optional.empty();
        } catch (IOException ex) {
            return Optional.empty();
        }
    }
}
