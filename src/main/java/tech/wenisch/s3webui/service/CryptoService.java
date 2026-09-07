package tech.wenisch.s3webui.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * AES-256-GCM encryption for secrets held in the database.
 *
 * <p>The key comes from {@code app.encryption-key} (base64) when set, otherwise from
 * {@code ${app.data-dir}/encryption.key}, which is generated on first start. That file must survive
 * restarts - without it, stored S3 secret keys can no longer be decrypted.
 */
@Slf4j
@Service
public class CryptoService {

    static final String KEY_FILE_NAME = "encryption.key";

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final String configuredKey;
    private final Path dataDir;

    private SecretKeySpec key;

    public CryptoService(@Value("${app.encryption-key:}") String configuredKey,
                         @Value("${app.data-dir:./data}") String dataDir) {
        this.configuredKey = configuredKey;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    void init() {
        this.key = new SecretKeySpec(resolveKeyBytes(), ALGORITHM);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length <= IV_BYTES) {
                throw new IllegalArgumentException("Ciphertext is too short");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SecretDecryptionException(
                    "Unable to decrypt a stored secret. The encryption key no longer matches the data: "
                            + "check APP_ENCRYPTION_KEY, or restore "
                            + dataDir.resolve(KEY_FILE_NAME).toAbsolutePath() + " from a backup.", e);
        }
    }

    private byte[] resolveKeyBytes() {
        if (configuredKey != null && !configuredKey.isBlank()) {
            byte[] decoded = decodeBase64(configuredKey.trim(), "APP_ENCRYPTION_KEY must be base64 encoded");
            if (decoded.length != KEY_BYTES) {
                throw new IllegalStateException(
                        "APP_ENCRYPTION_KEY must decode to " + KEY_BYTES + " bytes, got " + decoded.length);
            }
            log.info("Using the encryption key supplied through APP_ENCRYPTION_KEY.");
            return decoded;
        }

        Path keyFile = dataDir.resolve(KEY_FILE_NAME);
        try {
            if (Files.exists(keyFile)) {
                byte[] decoded = decodeBase64(Files.readString(keyFile).trim(),
                        "Encryption key file " + keyFile.toAbsolutePath() + " is not valid base64");
                if (decoded.length != KEY_BYTES) {
                    throw new IllegalStateException("Encryption key file " + keyFile.toAbsolutePath()
                            + " is corrupt: expected " + KEY_BYTES + " bytes, got " + decoded.length);
                }
                return decoded;
            }
            return generateKeyFile(keyFile);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read or create encryption key file " + keyFile.toAbsolutePath(), e);
        }
    }

    private byte[] generateKeyFile(Path keyFile) throws IOException {
        byte[] generated;
        try {
            KeyGenerator generator = KeyGenerator.getInstance(ALGORITHM);
            generator.init(KEY_BYTES * 8);
            generated = generator.generateKey().getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate an encryption key", e);
        }

        Path parent = keyFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(generated));
        restrictPermissions(keyFile);

        log.warn("*** Generated a new encryption key at {}. Back this file up and keep it on a persistent "
                + "volume - stored S3 secrets cannot be decrypted without it. Set APP_ENCRYPTION_KEY to "
                + "supply your own key instead. ***", keyFile.toAbsolutePath());
        return generated;
    }

    private void restrictPermissions(Path keyFile) {
        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(keyFile, ownerOnly);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows and some container filesystems have no POSIX permissions; not fatal.
            log.debug("Could not restrict permissions on {}", keyFile, e);
        }
    }

    private byte[] decodeBase64(String value, String message) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(message, e);
        }
    }
}
