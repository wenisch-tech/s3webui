package tech.wenisch.s3webui.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tech.wenisch.s3webui.config.IamProperties;
import tech.wenisch.s3webui.entity.S3Credential;
import tech.wenisch.s3webui.model.iam.IamAccessKey;
import tech.wenisch.s3webui.repository.S3CredentialRepository;
import tech.wenisch.s3webui.service.iam.IamProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamServiceTest {

    private final IamProperties properties = new IamProperties();
    private final IamProvider provider = mock(IamProvider.class);
    private final S3CredentialService credentialService = mock(S3CredentialService.class);
    private final S3CredentialRepository credentialRepository = mock(S3CredentialRepository.class);
    private final S3ConnectionSettingsService settingsService = mock(S3ConnectionSettingsService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<IamProvider> providerSource = mock(ObjectProvider.class);

    private IamService newService() {
        return new IamService(properties, providerSource, credentialService, credentialRepository, settingsService);
    }

    private void enable() {
        properties.setEnabled(true);
        when(providerSource.getIfAvailable()).thenReturn(provider);
    }

    private void withSettings(String endpoint, String region) {
        when(settingsService.getEffectiveSettingsOrThrow()).thenReturn(
                new S3ConnectionSettingsService.EffectiveS3Settings("admin", "admin-secret", endpoint, region, false));
        when(credentialService.create(any(), any())).thenAnswer(invocation -> {
            var form = (S3CredentialService.CredentialForm) invocation.getArgument(0);
            var credential = new S3Credential();
            credential.setId(7L);
            credential.setName(form.name());
            return credential;
        });
    }

    @Test
    void aNewUsersKeyIsStoredAsAnUngrantedS3Key() {
        enable();
        withSettings("https://minio.example.com", "eu-central-1");
        when(provider.createUser("alice"))
                .thenReturn(new IamAccessKey("alice", "AKIA1", "s3cr3t"));
        when(credentialRepository.findByNameIgnoreCase(any())).thenReturn(Optional.empty());

        var stored = newService().createUserAndStoreKey("alice", "admin@example.com");

        ArgumentCaptor<S3CredentialService.CredentialForm> captor =
                ArgumentCaptor.forClass(S3CredentialService.CredentialForm.class);
        verify(credentialService).create(captor.capture(), eq("admin@example.com"));
        var form = captor.getValue();

        assertEquals("IAM: alice", form.name());
        assertEquals("https://minio.example.com", form.endpointUrl());
        assertEquals("eu-central-1", form.region());
        assertEquals("AKIA1", form.accessKey());
        assertEquals("s3cr3t", form.secretKey());
        assertTrue(form.enabled());
        assertTrue(form.grants().isEmpty(), "a new IAM key must not be granted to anyone yet");

        assertEquals("AKIA1", stored.accessKeyId());
        assertEquals(7L, stored.credentialId());
    }

    @Test
    void aClashingKeyNameIsSuffixedRatherThanRejected() {
        enable();
        withSettings("https://minio.example.com", "us-east-1");
        when(provider.createAccessKey("alice")).thenReturn(new IamAccessKey("alice", "AKIA2", "x"));
        when(credentialRepository.findByNameIgnoreCase("IAM: alice"))
                .thenReturn(Optional.of(new S3Credential()));
        when(credentialRepository.findByNameIgnoreCase("IAM: alice (2)")).thenReturn(Optional.empty());

        newService().createAccessKeyAndStore("alice", "admin@example.com");

        ArgumentCaptor<S3CredentialService.CredentialForm> captor =
                ArgumentCaptor.forClass(S3CredentialService.CredentialForm.class);
        verify(credentialService).create(captor.capture(), any());
        assertEquals("IAM: alice (2)", captor.getValue().name());
    }

    @Test
    void aSessionAgainstRealAwsGetsTheRegionalS3Endpoint() {
        enable();
        withSettings("", "eu-west-1");
        when(provider.createUser("bob")).thenReturn(new IamAccessKey("bob", "AKIA3", "y"));
        when(credentialRepository.findByNameIgnoreCase(any())).thenReturn(Optional.empty());

        newService().createUserAndStoreKey("bob", "admin@example.com");

        ArgumentCaptor<S3CredentialService.CredentialForm> captor =
                ArgumentCaptor.forClass(S3CredentialService.CredentialForm.class);
        verify(credentialService).create(captor.capture(), any());
        assertEquals("https://s3.eu-west-1.amazonaws.com", captor.getValue().endpointUrl());
    }

    @Test
    void theFeatureReportsUnavailableWhenTheFlagIsOff() {
        properties.setEnabled(false);

        var capabilities = newService().capabilities();

        assertFalse(capabilities.available());
        assertTrue(capabilities.reason().contains("disabled"));
        verify(providerSource, never()).getIfAvailable();
    }

    @Test
    void theFeatureReportsUnavailableWhenTheEndpointRejectsIamCalls() {
        enable();
        when(provider.listUsers()).thenThrow(new RuntimeException("NotImplemented"));

        var capabilities = newService().capabilities();

        assertFalse(capabilities.available());
        assertTrue(capabilities.reason().contains("NotImplemented"));
    }

    @Test
    void callingTheProviderWhileDisabledFailsLoudly() {
        properties.setEnabled(false);

        assertThrows(IllegalStateException.class, () -> newService().provider());
    }
}
