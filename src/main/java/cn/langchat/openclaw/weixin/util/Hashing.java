package cn.langchat.openclaw.weixin.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class Hashing {
    private Hashing() {
    }

    public static String md5Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >>> 4) & 0x0F, 16));
                sb.append(Character.forDigit(b & 0x0F, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm unavailable", e);
        }
    }
}
