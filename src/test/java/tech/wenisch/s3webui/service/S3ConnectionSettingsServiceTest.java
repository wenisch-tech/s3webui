package tech.wenisch.s3webui.service;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tech.wenisch.s3webui.model.ResolvedCredential;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3ConnectionSettingsServiceTest {

    private S3CredentialAccessService accessService;
    private AppSettingsService appSettingsService;
    private S3ConnectionSettingsService service;
    private MockHttpServletRequest request;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        accessService = mock(S3CredentialAccessService.class);
        appSettingsService = mock(AppSettingsService.class);
        when(appSettingsService.isUserSuppliedCredentialsAllowed()).thenReturn(true);

        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ObjectProvider<HttpSession> sessionProvider = mock(ObjectProvider.class);
        when(sessionProvider.getIfAvailable()).thenReturn(null);

        service = new S3ConnectionSettingsService(sessionProvider, accessService, appSettingsService);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob@example.com", "n/a", List.of()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void aFreshSessionHasToPickAKey() {
        assertThat(service.isSelectionRequired()).isTrue();
        assertThatThrownBy(service::getEffectiveSettingsOrThrow)
                .isInstanceOf(MissingS3ConfigurationException.class);
    }

    @Test
    void aSelectedKeyBecomesTheEffectiveSettings() {
        when(accessService.resolve(any(), eq("7"))).thenReturn(resolved());

        service.selectCredential("7");
        var settings = service.getEffectiveSettingsOrThrow();

        assertThat(service.isSelectionRequired()).isFalse();
        assertThat(settings.accessKey()).isEqualTo("AKIA");
        assertThat(settings.secretKey()).isEqualTo("secret");
        assertThat(settings.endpointUrl()).isEqualTo("https://s3.example.com");
        assertThat(settings.region()).isEqualTo("eu-central-1");
        assertThat(settings.insecureSkipTlsVerify()).isTrue();
        assertThat(service.getActiveCredentialName()).isEqualTo("Archive");
    }

    @Test
    void anUnauthorisedKeyNeverReachesTheSession() {
        when(accessService.resolve(any(), eq("7"))).thenThrow(new AccessDeniedException("nope"));

        assertThatThrownBy(() -> service.selectCredential("7")).isInstanceOf(AccessDeniedException.class);
        assertThat(service.isSelectionRequired()).isTrue();
    }

    @Test
    void revokingAGrantEndsAnAlreadyRunningSession() {
        when(accessService.resolve(any(), eq("7"))).thenReturn(resolved());
        service.selectCredential("7");

        when(accessService.resolve(any(), eq("7"))).thenThrow(new AccessDeniedException("revoked"));

        assertThat(service.isSelectionRequired()).isTrue();
        assertThatThrownBy(service::getEffectiveSettingsOrThrow).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ownCredentialsAreUsedWhenTheyAreAllowed() {
        service.selectOwnCredentials(new S3ConnectionSettingsService.SubmittedS3Settings(
                "own-access", "own-secret", "https://minio.local", null, true));

        var settings = service.getEffectiveSettingsOrThrow();

        assertThat(settings.accessKey()).isEqualTo("own-access");
        assertThat(settings.region()).isEqualTo("us-east-1");
        assertThat(service.getActiveCredentialName()).isEqualTo("Own credentials");
    }

    @Test
    void ownCredentialsCarryTheInsecureSkipTlsVerifyFlagThrough() {
        service.selectOwnCredentials(new S3ConnectionSettingsService.SubmittedS3Settings(
                "own-access", "own-secret", "https://minio.local", null, true));

        assertThat(service.getEffectiveSettingsOrThrow().insecureSkipTlsVerify()).isTrue();
    }

    @Test
    void ownCredentialsDefaultInsecureSkipTlsVerifyToFalse() {
        service.selectOwnCredentials(new S3ConnectionSettingsService.SubmittedS3Settings(
                "own-access", "own-secret", "https://minio.local", null, false));

        assertThat(service.getEffectiveSettingsOrThrow().insecureSkipTlsVerify()).isFalse();
    }

    @Test
    void ownCredentialsAreRejectedWhenTheAdministratorDisabledThem() {
        when(appSettingsService.isUserSuppliedCredentialsAllowed()).thenReturn(false);

        assertThatThrownBy(() -> service.selectOwnCredentials(
                new S3ConnectionSettingsService.SubmittedS3Settings(
                        "own-access", "own-secret", "https://minio.local", null, false)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void disablingOwnCredentialsCutsOffSessionsAlreadyUsingThem() {
        service.selectOwnCredentials(new S3ConnectionSettingsService.SubmittedS3Settings(
                "own-access", "own-secret", "https://minio.local", null, false));

        when(appSettingsService.isUserSuppliedCredentialsAllowed()).thenReturn(false);

        assertThat(service.isSelectionRequired()).isTrue();
    }

    @Test
    void incompleteOwnCredentialsAreRejected() {
        assertThatThrownBy(() -> service.selectOwnCredentials(
                new S3ConnectionSettingsService.SubmittedS3Settings("own-access", null, "https://minio.local", null, false)))
                .isInstanceOf(MissingS3ConfigurationException.class);
    }

    @Test
    void clearingTheSelectionSendsTheUserBackToThePicker() {
        when(accessService.resolve(any(), eq("7"))).thenReturn(resolved());
        service.selectCredential("7");

        service.clearSelection();

        assertThat(service.isSelectionRequired()).isTrue();
        assertThat(service.getActiveCredentialName()).isNull();
    }

    private static ResolvedCredential resolved() {
        return new ResolvedCredential("7", "Archive", "AKIA", "secret",
                "https://s3.example.com", "eu-central-1", true);
    }
}
