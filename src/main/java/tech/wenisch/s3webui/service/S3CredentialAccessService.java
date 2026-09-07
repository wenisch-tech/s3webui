package tech.wenisch.s3webui.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.s3webui.entity.GrantType;
import tech.wenisch.s3webui.entity.S3Credential;
import tech.wenisch.s3webui.entity.S3CredentialGrant;
import tech.wenisch.s3webui.model.ResolvedCredential;
import tech.wenisch.s3webui.model.S3CredentialView;
import tech.wenisch.s3webui.repository.S3CredentialRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which stored S3 keys a signed-in user may use.
 *
 * <p>Access is re-evaluated on every request rather than cached in the session, so revoking a grant
 * takes effect immediately without the user signing out.
 */
@Service
@RequiredArgsConstructor
public class S3CredentialAccessService {

    /** Id of the synthetic key backed by the {@code S3_*} environment variables. */
    public static final String ENV_CREDENTIAL_ID = "env";

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String GROUP_PREFIX = "GROUP_";
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final S3CredentialRepository credentialRepository;

    @Value("${s3.access-key:}")
    private String envAccessKey;

    @Value("${s3.secret-key:}")
    private String envSecretKey;

    @Value("${s3.endpoint-url:}")
    private String envEndpointUrl;

    @Value("${s3.region:}")
    private String envRegion;

    @Value("${s3.insecure-skip-tls-verify:false}")
    private boolean envInsecureSkipTlsVerify;

    public boolean hasEnvironmentCredential() {
        return hasText(envAccessKey) && hasText(envSecretKey) && hasText(envEndpointUrl);
    }

    /** Every key the user may pick, the built-in environment key first. */
    @Transactional(readOnly = true)
    public List<S3CredentialView> listAccessible(Authentication authentication) {
        List<S3CredentialView> views = new ArrayList<>();
        if (hasEnvironmentCredential()) {
            views.add(environmentView());
        }
        credentialRepository.findByEnabledTrueOrderByNameAsc().stream()
                .filter(credential -> isAccessible(credential, authentication))
                .map(S3CredentialAccessService::toView)
                .forEach(views::add);
        return views;
    }

    /** Resolves a key by id and fails if the user is not (or no longer) allowed to use it. */
    @Transactional(readOnly = true)
    public ResolvedCredential resolve(Authentication authentication, String credentialId) {
        if (ENV_CREDENTIAL_ID.equals(credentialId)) {
            if (!hasEnvironmentCredential()) {
                throw new MissingS3ConfigurationException("The environment S3 key is no longer configured");
            }
            return new ResolvedCredential(
                    ENV_CREDENTIAL_ID,
                    environmentName(),
                    envAccessKey.trim(),
                    envSecretKey.trim(),
                    envEndpointUrl.trim(),
                    hasText(envRegion) ? envRegion.trim() : "us-east-1",
                    envInsecureSkipTlsVerify);
        }

        S3Credential credential = parseId(credentialId)
                .flatMap(credentialRepository::findById)
                .orElseThrow(() -> new MissingS3ConfigurationException(
                        "The selected S3 key no longer exists. Pick another one."));

        if (!credential.isEnabled()) {
            throw new AccessDeniedException("The S3 key '" + credential.getName() + "' has been disabled");
        }
        if (!isAccessible(credential, authentication)) {
            throw new AccessDeniedException("You are not allowed to use the S3 key '" + credential.getName() + "'");
        }

        return new ResolvedCredential(
                String.valueOf(credential.getId()),
                credential.getName(),
                credential.getAccessKey(),
                credential.getSecretKey(),
                credential.getEndpointUrl(),
                hasText(credential.getRegion()) ? credential.getRegion() : "us-east-1",
                credential.isInsecureSkipTlsVerify());
    }

    boolean isAccessible(S3Credential credential, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        if (authorities.contains(ADMIN_AUTHORITY)) {
            return true;
        }

        String email = AuthenticationIdentity.emailOf(authentication);
        String principalName = authentication.getName();

        for (S3CredentialGrant grant : credential.getGrants()) {
            if (matches(grant, authorities, email, principalName)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(S3CredentialGrant grant, Set<String> authorities, String email, String principalName) {
        GrantType type = grant.getGrantType();
        if (type == GrantType.ALL_AUTHENTICATED) {
            return true;
        }

        String value = grant.getGrantValue();
        if (!hasText(value)) {
            return false;
        }
        String trimmed = value.trim();

        return switch (type) {
            case USER -> trimmed.equalsIgnoreCase(email) || trimmed.equalsIgnoreCase(principalName);
            case ROLE -> authorities.contains(ROLE_PREFIX + trimmed) || authorities.contains(trimmed);
            case GROUP -> authorities.contains(GROUP_PREFIX + trimmed)
                    || authorities.contains(GROUP_PREFIX + stripLeadingSlash(trimmed));
            case ALL_AUTHENTICATED -> true;
        };
    }

    private S3CredentialView environmentView() {
        return new S3CredentialView(
                ENV_CREDENTIAL_ID,
                environmentName(),
                envEndpointUrl.trim(),
                hasText(envRegion) ? envRegion.trim() : "us-east-1",
                true);
    }

    private String environmentName() {
        return "Environment (" + envEndpointUrl.trim() + ")";
    }

    private static S3CredentialView toView(S3Credential credential) {
        return new S3CredentialView(
                String.valueOf(credential.getId()),
                credential.getName(),
                credential.getEndpointUrl(),
                credential.getRegion(),
                false);
    }

    private static Optional<Long> parseId(String credentialId) {
        try {
            return Optional.of(Long.parseLong(credentialId));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String stripLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
