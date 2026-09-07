package tech.wenisch.s3webui.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tech.wenisch.s3webui.entity.AppUser;
import tech.wenisch.s3webui.entity.AuthProvider;
import tech.wenisch.s3webui.entity.EncryptedStringConverter;
import tech.wenisch.s3webui.entity.GrantType;
import tech.wenisch.s3webui.entity.S3Credential;
import tech.wenisch.s3webui.entity.S3CredentialGrant;
import tech.wenisch.s3webui.entity.UserRole;
import tech.wenisch.s3webui.service.CryptoService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the generated schema on H2 and checks that the encrypted column survives a round trip.
 */
@DataJpaTest
@Import({CryptoService.class, EncryptedStringConverter.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:persistence-smoke;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "app.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
class PersistenceSmokeTest {

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private S3CredentialRepository credentialRepository;

    @Test
    void storesAndFindsAUserByEmailIgnoringCase() {
        userRepository.save(AppUser.builder()
                .email("Admin@Example.com")
                .passwordHash("hash")
                .role(UserRole.ADMIN)
                .provider(AuthProvider.LOCAL)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());

        assertThat(userRepository.findByEmailIgnoreCase("admin@example.com")).isPresent();
        assertThat(userRepository.existsByEmailIgnoreCase("ADMIN@EXAMPLE.COM")).isTrue();
    }

    @Test
    void storesTheSecretKeyEncryptedAndReadsItBack() {
        S3Credential credential = S3Credential.builder()
                .name("Archive")
                .endpointUrl("https://s3.example.com")
                .region("eu-central-1")
                .accessKey("AKIA")
                .secretKey("top-secret")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .grants(new ArrayList<>())
                .build();
        credential.getGrants().add(S3CredentialGrant.builder()
                .credential(credential)
                .grantType(GrantType.ALL_AUTHENTICATED)
                .build());

        Long id = credentialRepository.saveAndFlush(credential).getId();
        credentialRepository.flush();

        S3Credential reloaded = credentialRepository.findById(id).orElseThrow();
        assertThat(reloaded.getSecretKey()).isEqualTo("top-secret");
        assertThat(reloaded.getGrants()).hasSize(1);
    }

    @Test
    void listsOnlyEnabledCredentials() {
        credentialRepository.save(S3Credential.builder()
                .name("Enabled").endpointUrl("https://a").region("eu-central-1")
                .accessKey("a").secretKey("b").enabled(true).grants(new ArrayList<>()).build());
        credentialRepository.save(S3Credential.builder()
                .name("Disabled").endpointUrl("https://b").region("eu-central-1")
                .accessKey("a").secretKey("b").enabled(false).grants(new ArrayList<>()).build());

        List<S3Credential> enabled = credentialRepository.findByEnabledTrueOrderByNameAsc();

        assertThat(enabled).extracting(S3Credential::getName).containsExactly("Enabled");
    }
}
