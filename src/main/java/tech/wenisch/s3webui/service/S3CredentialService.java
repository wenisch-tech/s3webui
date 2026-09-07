package tech.wenisch.s3webui.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.s3webui.entity.GrantType;
import tech.wenisch.s3webui.entity.S3Credential;
import tech.wenisch.s3webui.entity.S3CredentialGrant;
import tech.wenisch.s3webui.repository.S3CredentialRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Administrative CRUD over the stored S3 keys and the grants that hand them out.
 */
@Service
@RequiredArgsConstructor
public class S3CredentialService {

    private final S3CredentialRepository credentialRepository;

    @Transactional(readOnly = true)
    public List<S3Credential> listAll() {
        return credentialRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public S3Credential get(Long id) {
        return credentialRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No S3 key with id " + id));
    }

    @Transactional
    public S3Credential create(CredentialForm form, String createdBy) {
        validate(form, true);
        requireUniqueName(form.name(), null);

        S3Credential credential = S3Credential.builder()
                .name(form.name().trim())
                .endpointUrl(form.endpointUrl().trim())
                .region(blankToDefault(form.region()))
                .accessKey(form.accessKey().trim())
                .secretKey(form.secretKey())
                .insecureSkipTlsVerify(form.insecureSkipTlsVerify())
                .enabled(form.enabled())
                .createdAt(LocalDateTime.now())
                .createdBy(createdBy)
                .grants(new ArrayList<>())
                .build();

        applyGrants(credential, form.grants());
        return credentialRepository.save(credential);
    }

    @Transactional
    public S3Credential update(Long id, CredentialForm form) {
        S3Credential credential = get(id);
        validate(form, false);
        requireUniqueName(form.name(), id);

        credential.setName(form.name().trim());
        credential.setEndpointUrl(form.endpointUrl().trim());
        credential.setRegion(blankToDefault(form.region()));
        credential.setAccessKey(form.accessKey().trim());
        credential.setInsecureSkipTlsVerify(form.insecureSkipTlsVerify());
        credential.setEnabled(form.enabled());

        // A blank secret means "keep the stored one" - the panel never sends it back to the browser.
        if (form.secretKey() != null && !form.secretKey().isBlank()) {
            credential.setSecretKey(form.secretKey());
        }

        applyGrants(credential, form.grants());
        return credentialRepository.save(credential);
    }

    @Transactional
    public void delete(Long id) {
        credentialRepository.delete(get(id));
    }

    private void applyGrants(S3Credential credential, List<GrantForm> grantForms) {
        credential.getGrants().clear();
        if (grantForms == null) {
            return;
        }
        for (GrantForm grantForm : grantForms) {
            if (grantForm == null || grantForm.grantType() == null) {
                continue;
            }
            String value = grantForm.grantType() == GrantType.ALL_AUTHENTICATED
                    ? null
                    : trimToNull(grantForm.grantValue());
            if (grantForm.grantType() != GrantType.ALL_AUTHENTICATED && value == null) {
                throw new IllegalArgumentException(
                        "A " + grantForm.grantType() + " grant needs a value");
            }
            credential.getGrants().add(S3CredentialGrant.builder()
                    .credential(credential)
                    .grantType(grantForm.grantType())
                    .grantValue(value)
                    .build());
        }
    }

    private void validate(CredentialForm form, boolean secretRequired) {
        if (trimToNull(form.name()) == null) {
            throw new IllegalArgumentException("Name is required");
        }
        if (trimToNull(form.endpointUrl()) == null) {
            throw new IllegalArgumentException("Endpoint URL is required");
        }
        if (trimToNull(form.accessKey()) == null) {
            throw new IllegalArgumentException("Access key is required");
        }
        if (secretRequired && trimToNull(form.secretKey()) == null) {
            throw new IllegalArgumentException("Secret key is required");
        }
    }

    private void requireUniqueName(String name, Long allowedId) {
        Optional<S3Credential> existing = credentialRepository.findByNameIgnoreCase(name.trim());
        if (existing.isPresent() && !existing.get().getId().equals(allowedId)) {
            throw new IllegalArgumentException("An S3 key named " + name.trim() + " already exists");
        }
    }

    private static String blankToDefault(String region) {
        String trimmed = trimToNull(region);
        return trimmed == null ? "us-east-1" : trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record CredentialForm(
            String name,
            String endpointUrl,
            String region,
            String accessKey,
            String secretKey,
            boolean insecureSkipTlsVerify,
            boolean enabled,
            List<GrantForm> grants
    ) {
    }

    public record GrantForm(GrantType grantType, String grantValue) {
    }
}
