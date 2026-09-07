package tech.wenisch.s3webui.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import tech.wenisch.s3webui.entity.GrantType;
import tech.wenisch.s3webui.entity.S3Credential;
import tech.wenisch.s3webui.entity.S3CredentialGrant;
import tech.wenisch.s3webui.model.S3CredentialView;
import tech.wenisch.s3webui.repository.S3CredentialRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3CredentialAccessServiceTest {

    private S3CredentialRepository repository;
    private S3CredentialAccessService service;

    @BeforeEach
    void setUp() {
        repository = mock(S3CredentialRepository.class);
        service = new S3CredentialAccessService(repository);
        setEnvironmentCredential("", "", "");
    }

    @Test
    void everyoneSeesAKeyGrantedToAllAuthenticated() {
        stubCredentials(credential(1L, "Shared", grant(GrantType.ALL_AUTHENTICATED, null)));

        assertThat(names(service.listAccessible(user("bob@example.com")))).containsExactly("Shared");
    }

    @Test
    void aUserGrantMatchesOnEmailOnly() {
        stubCredentials(credential(1L, "Alice only", grant(GrantType.USER, "alice@example.com")));

        assertThat(service.listAccessible(user("alice@example.com"))).hasSize(1);
        assertThat(service.listAccessible(user("bob@example.com"))).isEmpty();
    }

    @Test
    void aUserGrantIgnoresCase() {
        stubCredentials(credential(1L, "Alice only", grant(GrantType.USER, "Alice@Example.com")));

        assertThat(service.listAccessible(user("alice@example.com"))).hasSize(1);
    }

    @Test
    void aRoleGrantMatchesAProviderRole() {
        stubCredentials(credential(1L, "Archive", grant(GrantType.ROLE, "archivist")));

        assertThat(service.listAccessible(user("bob@example.com", "ROLE_archivist"))).hasSize(1);
        assertThat(service.listAccessible(user("bob@example.com", "ROLE_reader"))).isEmpty();
    }

    @Test
    void aGroupGrantMatchesAProviderGroup() {
        stubCredentials(credential(1L, "Team", grant(GrantType.GROUP, "platform")));

        assertThat(service.listAccessible(user("bob@example.com", "GROUP_platform"))).hasSize(1);
    }

    @Test
    void aGroupGrantToleratesAKeycloakGroupPath() {
        stubCredentials(credential(1L, "Team", grant(GrantType.GROUP, "/platform")));

        assertThat(service.listAccessible(user("bob@example.com", "GROUP_platform"))).hasSize(1);
    }

    @Test
    void administratorsSeeEveryKey() {
        stubCredentials(credential(1L, "Ungranted"));

        assertThat(service.listAccessible(user("admin@example.com", "ROLE_ADMIN"))).hasSize(1);
    }

    @Test
    void aKeyWithoutGrantsIsInvisibleToOrdinaryUsers() {
        stubCredentials(credential(1L, "Ungranted"));

        assertThat(service.listAccessible(user("bob@example.com"))).isEmpty();
    }

    @Test
    void disabledKeysAreNeverListed() {
        // The repository query already filters on enabled, so nothing comes back.
        when(repository.findByEnabledTrueOrderByNameAsc()).thenReturn(List.of());

        assertThat(service.listAccessible(user("admin@example.com", "ROLE_ADMIN"))).isEmpty();
    }

    @Test
    void theEnvironmentKeyIsOfferedToEveryoneAndListedFirst() {
        setEnvironmentCredential("AKIA", "secret", "https://s3.example.com");
        stubCredentials(credential(1L, "Shared", grant(GrantType.ALL_AUTHENTICATED, null)));

        List<S3CredentialView> accessible = service.listAccessible(user("bob@example.com"));

        assertThat(accessible).hasSize(2);
        assertThat(accessible.get(0).builtIn()).isTrue();
        assertThat(accessible.get(0).id()).isEqualTo(S3CredentialAccessService.ENV_CREDENTIAL_ID);
        assertThat(accessible.get(1).builtIn()).isFalse();
    }

    @Test
    void theEnvironmentKeyIsHiddenWhenItIsIncomplete() {
        setEnvironmentCredential("AKIA", "secret", "");
        stubCredentials();

        assertThat(service.listAccessible(user("bob@example.com"))).isEmpty();
    }

    @Test
    void resolvingReturnsTheDecryptedSecret() {
        S3Credential credential = credential(7L, "Archive", grant(GrantType.ALL_AUTHENTICATED, null));
        credential.setAccessKey("AKIA7");
        credential.setSecretKey("plaintext-after-decryption");
        when(repository.findById(7L)).thenReturn(Optional.of(credential));

        var resolved = service.resolve(user("bob@example.com"), "7");

        assertThat(resolved.accessKey()).isEqualTo("AKIA7");
        assertThat(resolved.secretKey()).isEqualTo("plaintext-after-decryption");
        assertThat(resolved.region()).isEqualTo("eu-central-1");
    }

    @Test
    void resolvingFallsBackToTheDefaultRegion() {
        S3Credential credential = credential(7L, "Archive", grant(GrantType.ALL_AUTHENTICATED, null));
        credential.setRegion("");
        when(repository.findById(7L)).thenReturn(Optional.of(credential));

        assertThat(service.resolve(user("bob@example.com"), "7").region()).isEqualTo("us-east-1");
    }

    @Test
    void resolvingFailsOnceAGrantHasBeenRevoked() {
        S3Credential credential = credential(7L, "Archive");
        when(repository.findById(7L)).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.resolve(user("bob@example.com"), "7"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void resolvingFailsForADisabledKey() {
        S3Credential credential = credential(7L, "Archive", grant(GrantType.ALL_AUTHENTICATED, null));
        credential.setEnabled(false);
        when(repository.findById(7L)).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.resolve(user("bob@example.com"), "7"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void resolvingFailsForADeletedKey() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(user("bob@example.com"), "7"))
                .isInstanceOf(MissingS3ConfigurationException.class);
    }

    @Test
    void anonymousCallersSeeNothing() {
        stubCredentials(credential(1L, "Shared", grant(GrantType.ALL_AUTHENTICATED, null)));

        assertThat(service.listAccessible(null)).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void stubCredentials(S3Credential... credentials) {
        when(repository.findByEnabledTrueOrderByNameAsc()).thenReturn(List.of(credentials));
    }

    private void setEnvironmentCredential(String accessKey, String secretKey, String endpointUrl) {
        ReflectionTestUtils.setField(service, "envAccessKey", accessKey);
        ReflectionTestUtils.setField(service, "envSecretKey", secretKey);
        ReflectionTestUtils.setField(service, "envEndpointUrl", endpointUrl);
        ReflectionTestUtils.setField(service, "envRegion", "");
        ReflectionTestUtils.setField(service, "envInsecureSkipTlsVerify", false);
    }

    private static S3Credential credential(Long id, String name, S3CredentialGrant... grants) {
        S3Credential credential = S3Credential.builder()
                .id(id)
                .name(name)
                .endpointUrl("https://s3.example.com")
                .region("eu-central-1")
                .accessKey("AKIA")
                .secretKey("secret")
                .enabled(true)
                .grants(new ArrayList<>(List.of(grants)))
                .build();
        credential.getGrants().forEach(grant -> grant.setCredential(credential));
        return credential;
    }

    private static S3CredentialGrant grant(GrantType type, String value) {
        return S3CredentialGrant.builder().grantType(type).grantValue(value).build();
    }

    private static Authentication user(String email, String... authorities) {
        return new UsernamePasswordAuthenticationToken(email, "n/a",
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }

    private static List<String> names(List<S3CredentialView> views) {
        return views.stream().map(S3CredentialView::name).toList();
    }
}
