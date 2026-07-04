package cn.cai.vtjserver.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM symmetric cipher used to encrypt sensitive secrets (LLM {@code apiKey}) at rest.
 *
 * <p>The 256-bit key is derived (SHA-256) from {@code vtj.ai.secret} (env {@code VTJ_AI_SECRET}).
 * When the property is blank a deterministic development key is derived and a warning is logged,
 * so the application still boots locally but never silently ships a hardcoded production key
 * (AGENTS.md §5.6 / §10.6).
 *
 * <p>Ciphertext format is {@code base64(iv[12] || ciphertext || gcmTag[16])}; a fresh random IV is
 * generated per call, so encrypting the same plaintext twice yields different outputs. Tampering is
 * detected by GCM and surfaced as an {@link IllegalStateException} on decrypt.
 */
@Slf4j
@Component
public class AesGcmCipher {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(@Value("${vtj.ai.secret:}") String secret) {
        String material = secret;
        if (material == null || material.isBlank()) {
            // Never fail startup, but make the insecure default loud: production must set VTJ_AI_SECRET.
            log.warn("vtj.ai.secret (VTJ_AI_SECRET) is not set; falling back to an insecure development key. "
                    + "Do NOT use this in production.");
            material = "vtj-insecure-dev-secret";
        }
        this.key = new SecretKeySpec(sha256(material), "AES");
    }

    /**
     * Encrypts a plaintext secret.
     *
     * @param plain the raw value; {@code null} or blank is returned unchanged (nothing to protect)
     * @return base64 ciphertext, or the original blank/null value
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt(String)}.
     *
     * @param encoded base64 ciphertext; {@code null} or blank is returned unchanged
     * @return the decrypted plaintext
     * @throws IllegalStateException if the input is malformed or has been tampered with
     */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return encoded;
        }
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt secret (malformed or tampered ciphertext)", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
