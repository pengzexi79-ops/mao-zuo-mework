package com.douyin.mixcut.security;

import com.douyin.mixcut.config.AppProps;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Encrypts provider credentials in the database using only APP_MASTER_KEY supplied at runtime. */
@Component
@RequiredArgsConstructor
public class CredentialCipher {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final AppProps props;
    private final SecureRandom random = new SecureRandom();

    public boolean available() {
        return props.getMasterKey() != null && !props.getMasterKey().isBlank();
    }

    public boolean encrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return "";
        if (encrypted(plain)) return plain;
        ensureAvailable();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("无法加密凭据", e);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) return "";
        if (!encrypted(value)) return value; // Backward-compatible until startup migration can run.
        ensureAvailable();
        try {
            String[] parts = value.split(":", 4);
            if (parts.length != 4) throw new IllegalArgumentException("凭据密文格式无效");
            byte[] iv = Base64.getUrlDecoder().decode(parts[2]);
            byte[] payload = Base64.getUrlDecoder().decode(parts[3]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(payload), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("无法解密凭据，请检查 APP_MASTER_KEY 是否与保存时一致", e);
        }
    }

    private SecretKeySpec key() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(props.getMasterKey().getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(hash, "AES");
    }

    private void ensureAvailable() {
        if (!available()) throw new IllegalStateException("未配置 APP_MASTER_KEY，拒绝保存第三方服务密钥");
    }
}
