package com.mindcare.global.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stores sensitive text as nonce(12 bytes) + AES-256-GCM ciphertext. */
@Component
public class SensitiveTextCodec {
    private static final int NONCE_LENGTH = 12;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SensitiveTextCodec(@Value("${app.crypto.encryption-key}") String encodedKey) {
        byte[] decoded = Base64.getDecoder().decode(encodedKey);
        if (decoded.length != 32) throw new IllegalArgumentException("app.crypto.encryption-key must be a base64 32-byte key");
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public byte[] encrypt(String plainText) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Unable to encrypt sensitive text", e); }
    }

    public String decrypt(byte[] encrypted) {
        try {
            ByteBuffer data = ByteBuffer.wrap(encrypted);
            byte[] nonce = new byte[NONCE_LENGTH];
            data.get(nonce);
            byte[] body = new byte[data.remaining()];
            data.get(body);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to decrypt sensitive text", e);
        }
    }
}
