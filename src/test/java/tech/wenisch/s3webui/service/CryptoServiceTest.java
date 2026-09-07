package tech.wenisch.s3webui.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void encryptsAndDecryptsRoundTrip(@TempDir Path dataDir) {
        CryptoService service = newService("", dataDir);

        String encrypted = service.encrypt("s3-secret-key");

        assertThat(encrypted).isNotEqualTo("s3-secret-key");
        assertThat(service.decrypt(encrypted)).isEqualTo("s3-secret-key");
    }

    @Test
    void usesAFreshIvForEveryEncryption(@TempDir Path dataDir) {
        CryptoService service = newService(KEY, dataDir);

        assertThat(service.encrypt("same")).isNotEqualTo(service.encrypt("same"));
    }

    @Test
    void generatesAndReusesTheKeyFile(@TempDir Path dataDir) {
        CryptoService first = newService("", dataDir);
        String encrypted = first.encrypt("persisted");

        assertThat(dataDir.resolve("encryption.key")).exists();

        CryptoService restarted = newService("", dataDir);
        assertThat(restarted.decrypt(encrypted)).isEqualTo("persisted");
    }

    @Test
    void rejectsCiphertextEncryptedWithAnotherKey(@TempDir Path dataDir, @TempDir Path otherDir) {
        String encrypted = newService("", dataDir).encrypt("secret");
        CryptoService otherKey = newService("", otherDir);

        assertThatThrownBy(() -> otherKey.decrypt(encrypted))
                .isInstanceOf(SecretDecryptionException.class)
                .hasMessageContaining("encryption key no longer matches");
    }

    @Test
    void rejectsTamperedCiphertext(@TempDir Path dataDir) {
        CryptoService service = newService(KEY, dataDir);
        String encrypted = service.encrypt("secret");
        String tampered = encrypted.substring(0, encrypted.length() - 2)
                + (encrypted.endsWith("A=") ? "B=" : "A=");

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    void rejectsAConfiguredKeyOfTheWrongLength(@TempDir Path dataDir) {
        CryptoService service = new CryptoService("c2hvcnQ=", dataDir.toString());

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void keepsTheKeyFileUntouchedWhenAKeyIsConfigured(@TempDir Path dataDir) {
        newService(KEY, dataDir);

        assertThat(Files.exists(dataDir.resolve("encryption.key"))).isFalse();
    }

    private CryptoService newService(String configuredKey, Path dataDir) {
        CryptoService service = new CryptoService(configuredKey, dataDir.toString());
        service.init();
        return service;
    }
}
