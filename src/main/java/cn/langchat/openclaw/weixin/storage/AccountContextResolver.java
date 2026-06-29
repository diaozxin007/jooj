package cn.langchat.openclaw.weixin.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class AccountContextResolver {
    private AccountContextResolver() {
    }

    public static String resolveOutboundAccountId(List<String> allAccountIds, String toUserId, FileContextTokenStore contextTokenStore) {
        if (allAccountIds == null || allAccountIds.isEmpty()) {
            throw new IllegalStateException("weixin: no accounts registered");
        }
        if (allAccountIds.size() == 1) {
            return allAccountIds.get(0);
        }

        List<String> matched = new ArrayList<>();
        for (String accountId : allAccountIds) {
            if (contextTokenStore.get(accountId, toUserId).isPresent()) {
                matched.add(accountId);
            }
        }

        if (matched.size() == 1) {
            return matched.get(0);
        }
        if (matched.isEmpty()) {
            throw new IllegalStateException("weixin: cannot resolve account for to=" + toUserId + " (no active context token)");
        }
        throw new IllegalStateException("weixin: ambiguous account for to=" + toUserId + " matched=" + String.join(",", matched));
    }
}
