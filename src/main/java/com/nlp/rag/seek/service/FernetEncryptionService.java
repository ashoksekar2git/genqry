package com.nlp.rag.seek.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * Java-native Fernet-compatible encryption service.
 *
 * Fernet specification (https://github.com/fernet/spec/blob/master/Spec.md):
 *
 *   Key  : 32 bytes = 16-byte signing key (HMAC-SHA256) + 16-byte encryption key (AES-128-CBC)
 *   Token: Version(1) | Timestamp(8) | IV(16) | CipherText(n) | HMAC(32)
 *          → URL-safe Base64-encoded
 *
 * A Fernet key is itself stored as URL-safe Base64 of the 32 raw bytes.
 *
 * If {@code genqry.auth.fernet.key} is not set, a random key is generated at
 * startup and logged once (copy it into application.properties for persistence
 * across restarts).
 *
 * Tokens produced here are readable by Python's cryptography.fernet.Fernet
 * when the same key is used.
 */
@Service
public class FernetEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(FernetEncryptionService.class);

    private static final byte   FERNET_VERSION   = (byte) 0x80;
    private static final int    SIGNING_KEY_LEN  = 16;
    private static final int    ENCRYPTION_KEY_LEN = 16;
    private static final int    IV_LEN           = 16;
    private static final int    HMAC_LEN         = 32;
    private static final String AES_ALGORITHM    = "AES/CBC/PKCS5Padding";
    private static final String HMAC_ALGORITHM   = "HmacSHA256";

    private volatile byte[] signingKey;
    private volatile byte[] encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public FernetEncryptionService(
            @Value("${genqry.auth.fernet.key:}") String configuredKey) {

        byte[] rawKey;

        if (configuredKey != null && !configuredKey.isBlank()) {
            rawKey = Base64.getUrlDecoder().decode(configuredKey.trim());
            if (rawKey.length != 32) {
                throw new IllegalArgumentException(
                        "genqry.auth.fernet.key must be a URL-safe Base64 encoding of exactly 32 bytes. " +
                        "Generate one with: FernetEncryptionService.generateKeyBase64()");
            }
            log.info("Fernet: loaded key from configuration");
        } else {
            rawKey = new byte[32];
            secureRandom.nextBytes(rawKey);
            String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(rawKey);
            log.warn("╔══════════════════════════════════════════════════════════════╗");
            log.warn("║  Fernet key NOT configured — generated a random key.         ║");
            log.warn("║  Add to application.properties to persist across restarts:   ║");
            log.warn("║  genqry.auth.fernet.key={}  ║", generated);
            log.warn("╚══════════════════════════════════════════════════════════════╝");
        }

        // First 16 bytes = signing key, last 16 = encryption key  (Fernet spec)
        signingKey    = Arrays.copyOfRange(rawKey, 0, SIGNING_KEY_LEN);
        encryptionKey = Arrays.copyOfRange(rawKey, SIGNING_KEY_LEN, SIGNING_KEY_LEN + ENCRYPTION_KEY_LEN);
    }

    /**
     * Re-initialise with a new Fernet key from the bootstrap secrets file.
     * Called by SecretBootstrapService after loading thiravucoal.json.
     *
     * @param fernetKeyBase64  URL-safe Base64 encoding of a 32-byte key
     */
    public void reinitialize(String fernetKeyBase64) {
        byte[] rawKey = Base64.getUrlDecoder().decode(fernetKeyBase64.trim());
        if (rawKey.length != 32) {
            throw new IllegalArgumentException("Fernet key must be exactly 32 bytes");
        }
        signingKey    = Arrays.copyOfRange(rawKey, 0, SIGNING_KEY_LEN);
        encryptionKey = Arrays.copyOfRange(rawKey, SIGNING_KEY_LEN, SIGNING_KEY_LEN + ENCRYPTION_KEY_LEN);
        log.info("Fernet: re-initialised with bootstrapped key");
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Encrypts {@code plaintext} using Fernet (AES-128-CBC + HMAC-SHA256).
     *
     * @param plaintext  the string to encrypt
     * @return           URL-safe Base64 Fernet token string
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv        = new byte[IV_LEN];
            secureRandom.nextBytes(iv);

            byte[] timestamp = longToBytes(Instant.now().getEpochSecond());
            byte[] ciphertext = aesCbcEncrypt(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8), iv);

            // Build the payload to HMAC: version(1) + timestamp(8) + iv(16) + ciphertext
            byte[] payload = concat(
                    new byte[]{ FERNET_VERSION },
                    timestamp,
                    iv,
                    ciphertext
            );

            byte[] hmac = hmacSha256(payload);

            // Final token = payload + hmac, URL-safe Base64 encoded
            byte[] token = concat(payload, hmac);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);

        } catch (Exception e) {
            throw new RuntimeException("Fernet encryption failed", e);
        }
    }

    /**
     * Decrypts a Fernet token back to the original plaintext string.
     *
     * @param fernetToken  the URL-safe Base64 Fernet token
     * @return             original plaintext
     * @throws IllegalArgumentException if the token is malformed or HMAC invalid
     */
    public String decrypt(String fernetToken) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(fernetToken.trim());

            // Minimum length: 1 + 8 + 16 + 16 (min AES block) + 32 = 73
            if (raw.length < 73) {
                throw new IllegalArgumentException("Fernet token too short");
            }
            if (raw[0] != FERNET_VERSION) {
                throw new IllegalArgumentException("Unsupported Fernet version: " + raw[0]);
            }

            // Split off HMAC (last 32 bytes) and verify
            byte[] payload  = Arrays.copyOfRange(raw, 0, raw.length - HMAC_LEN);
            byte[] givenHmac = Arrays.copyOfRange(raw, raw.length - HMAC_LEN, raw.length);
            byte[] expectedHmac = hmacSha256(payload);

            if (!constantTimeEquals(expectedHmac, givenHmac)) {
                throw new IllegalArgumentException("Fernet HMAC verification failed — token invalid or tampered");
            }

            // Extract IV (bytes 9–24) and ciphertext (bytes 25 to payload.end)
            byte[] iv         = Arrays.copyOfRange(payload, 9, 9 + IV_LEN);
            byte[] ciphertext = Arrays.copyOfRange(payload, 9 + IV_LEN, payload.length);

            byte[] plainBytes = aesCbcDecrypt(ciphertext, iv);
            return new String(plainBytes, java.nio.charset.StandardCharsets.UTF_8);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Fernet decryption failed", e);
        }
    }

    /**
     * Generates a new random Fernet key and returns it as URL-safe Base64.
     * Use this to generate the value for {@code genqry.auth.fernet.key}.
     */
    public static String generateKeyBase64() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    // =========================================================================
    // Crypto internals
    // =========================================================================

    private byte[] aesCbcEncrypt(byte[] plaintext, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(encryptionKey, "AES"),
                new IvParameterSpec(iv));
        return cipher.doFinal(plaintext);
    }

    private byte[] aesCbcDecrypt(byte[] ciphertext, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(encryptionKey, "AES"),
                new IvParameterSpec(iv));
        return cipher.doFinal(ciphertext);
    }

    private byte[] hmacSha256(byte[] data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
        return mac.doFinal(data);
    }

    private static byte[] longToBytes(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    /** Constant-time equality to prevent timing attacks. */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i]);
        return diff == 0;
    }
}

