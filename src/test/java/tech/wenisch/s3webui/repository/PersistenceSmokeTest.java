package tech.wenisch.s3webui.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tech.wenisch.s3webui.entity.AppUser;
import tech.wenisch.s3webui.entity.AuthProvider;
import tech.wenisch.s3webui.entity.EncryptedStringConverter;
import tech.wenisch.s3webui.entity.GrantType;
import tech.wenisch.s3webui.entity.MigrationJob;
import tech.wenisch.s3webui.entity.S3Credential;
import tech.wenisch.s3webui.entity.S3CredentialGrant;
import tech.wenisch.s3webui.entity.UserRole;
import tech.wenisch.s3webui.model.ConflictPolicy;
import tech.wenisch.s3webui.model.MigrationStatus;
import tech.wenisch.s3webui.service.CryptoService;

import java.time.Instant;
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

    @Autowired
    private MigrationJobRepository migrationJobRepository;

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

    @Test
    void storesMigrationJobsAndFindsTheUnfinishedOnes() {
        migrationJobRepository.save(migrationJob("running", MigrationStatus.RUNNING, null, "x".repeat(5000)));
        migrationJobRepository.save(migrationJob("done", MigrationStatus.COMPLETED, Instant.parse("2025-01-01T00:00:00Z"), "[]"));

        assertThat(migrationJobRepository.findByStatusIn(MigrationStatus.activeStatuses()))
                .extracting(MigrationJob::getId).containsExactly("running");
        assertThat(migrationJobRepository.findById("running")).get()
                .extracting(MigrationJob::getSelectedKeys).isEqualTo("x".repeat(5000));
        assertThat(migrationJobRepository.findAllByOrderByCreatedAtDesc(org.springframework.data.domain.PageRequest.of(0, 10)))
                .hasSize(2);
        assertThat(migrationJobRepository.findByOwnerNameOrderByCreatedAtDesc("nobody",
                org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();

        assertThat(migrationJobRepository.deleteByFinishedAtBefore(Instant.parse("2026-01-01T00:00:00Z"))).isEqualTo(1);
        assertThat(migrationJobRepository.findById("done")).isEmpty();
        assertThat(migrationJobRepository.findById("running")).isPresent();
    }

    private static MigrationJob migrationJob(String id, MigrationStatus status, Instant finishedAt, String keys) {
        return MigrationJob.builder()
                .id(id)
                .ownerName("alice@example.com")
                .status(status)
                .conflictPolicy(ConflictPolicy.SKIP)
                .selectedKeys(keys)
                .createdAt(Instant.parse("2025-01-01T00:00:00Z"))
                .finishedAt(finishedAt)
                .build();
    }
}
