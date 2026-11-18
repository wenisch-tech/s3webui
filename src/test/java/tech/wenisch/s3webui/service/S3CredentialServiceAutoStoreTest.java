package tech.wenisch.s3webui.service;

import org.junit.jupiter.api.Test;
import tech.wenisch.s3webui.entity.GrantType;
import tech.wenisch.s3webui.entity.S3Credential;
import tech.wenisch.s3webui.repository.S3CredentialRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3CredentialServiceAutoStoreTest {

    private final S3CredentialRepository repository = mock(S3CredentialRepository.class);
    private final S3CredentialService service = new S3CredentialService(repository);

    @Test
    void aNewKeyIsStoredAndHandedToEveryone() {
        when(repository.findByNameIgnoreCase("AKIA @ s3.example.com")).thenReturn(Optional.empty());
        when(repository.save(any(S3Credential.class))).thenAnswer(inv -> inv.getArgument(0));

        S3Credential saved = service.saveAutoStored("AKIA", "secret", "https://s3.example.com/", null, false, "tester");

        assertThat(saved.getName()).isEqualTo("AKIA @ s3.example.com");
        assertThat(saved.getRegion()).isEqualTo("us-east-1");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getGrants()).singleElement()
                .satisfies(grant -> assertThat(grant.getGrantType()).isEqualTo(GrantType.ALL_AUTHENTICATED));
    }

    @Test
    void enteringTheSameKeyAgainUpdatesTheStoredOne() {
        S3Credential existing = S3Credential.builder().id(5L).name("AKIA @ s3.example.com")
                .accessKey("AKIA").secretKey("old").endpointUrl("https://s3.example.com").enabled(false).build();
        when(repository.findByNameIgnoreCase("AKIA @ s3.example.com")).thenReturn(Optional.of(existing));
        when(repository.save(any(S3Credential.class))).thenAnswer(inv -> inv.getArgument(0));

        S3Credential saved = service.saveAutoStored("AKIA", "new", "https://s3.example.com", "eu-west-1", true, "tester");

        assertThat(saved.getId()).isEqualTo(5L);
        assertThat(saved.getSecretKey()).isEqualTo("new");
        assertThat(saved.getRegion()).isEqualTo("eu-west-1");
        assertThat(saved.isInsecureSkipTlsVerify()).isTrue();
        assertThat(saved.isEnabled()).isTrue();
        verify(repository, never()).delete(any());
    }
}
