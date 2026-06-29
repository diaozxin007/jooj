package cn.langchat.openclaw.weixin.media;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class AesEcb {
    private AesEcb() {
    }

    public static byte[] encrypt(byte[] plain, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(plain);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES encrypt failed", ex);
        }
    }

    public static byte[] decrypt(byte[] cipherText, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(cipherText);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES decrypt failed", ex);
        }
    }

    public static int paddedSize(int plainSize) {
        return ((plainSize + 1 + 15) / 16) * 16;
    }
}
