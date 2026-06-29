package cn.langchat.openclaw.weixin.util;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class IdGenerator {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private IdGenerator() {
    }

    public static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        SECURE_RANDOM.nextBytes(value);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : value) {
            sb.append(Character.forDigit((b >>> 4) & 0x0F, 16));
            sb.append(Character.forDigit(b & 0x0F, 16));
        }
        return sb.toString();
    }

    public static String clientId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    public static String randomWechatUinHeader() {
        byte[] raw = new byte[4];
        SECURE_RANDOM.nextBytes(raw);
        long uint32 = ((long) (raw[0] & 0xff) << 24)
            | ((long) (raw[1] & 0xff) << 16)
            | ((long) (raw[2] & 0xff) << 8)
            | ((long) (raw[3] & 0xff));
        return Base64.getEncoder().encodeToString(String.valueOf(uint32).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
