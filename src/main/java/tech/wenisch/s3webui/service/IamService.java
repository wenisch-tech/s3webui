package tech.wenisch.s3webui.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tech.wenisch.s3webui.config.IamProperties;
import tech.wenisch.s3webui.entity.S3Credential;
import tech.wenisch.s3webui.model.iam.IamAccessKey;
import tech.wenisch.s3webui.model.iam.IamCapabilities;
import tech.wenisch.s3webui.model.iam.IamUser;
import tech.wenisch.s3webui.repository.S3CredentialRepository;
import tech.wenisch.s3webui.service.iam.IamProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps the {@link IamProvider} with the two things the provider should not know about: whether the
 * feature is usable at all, and what to do with a freshly minted access key.
 */
@Slf4j
@Service
public class IamService {

    private final IamProperties iamProperties;
    private final ObjectProvider<IamProvider> providerSource;
    private final S3CredentialService credentialService;
    private final S3CredentialRepository credentialRepository;
    private final S3ConnectionSettingsService settingsService;

    public IamService(IamProperties iamProperties,
                      ObjectProvider<IamProvider> providerSource,
                      S3CredentialService credentialService,
                      S3CredentialRepository credentialRepository,
                      S3ConnectionSettingsService settingsService) {
        this.iamProperties = iamProperties;
        this.providerSource = providerSource;
        this.credentialService = credentialService;
        this.credentialRepository = credentialRepository;
        this.settingsService = settingsService;
    }

    /**
     * The provider for this request.
     *
     * @throws IllegalStateException when IAM is switched off, so callers surface a clean 409 rather
     *                               than a bean-lookup failure
     */
    public IamProvider provider() {
        if (!iamProperties.isEnabled()) {
            throw new IllegalStateException("IAM management is disabled on this deployment");
        }
        IamProvider provider = providerSource.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException("No IAM provider is configured for the selected S3 key");
        }
        return provider;
    }

    /**
     * What the UI may show. The provider probes its own endpoint, because being wired says nothing
     * about which IAM operations actually answer: MinIO implements none of them, and Ceph RGW
     * implements users and groups but not standalone policies.
     */
    public IamCapabilities capabilities() {
        if (!iamProperties.isEnabled()) {
            return IamCapabilities.unavailable("IAM management is disabled on this deployment");
        }
        IamProvider provider = providerSource.getIfAvailable();
        if (provider == null) {
            return IamCapabilities.unavailable("No IAM provider is configured for the selected S3 key");
        }
        try {
            return provider.capabilities();
        } catch (RuntimeException ex) {
            log.debug("IAM probe failed for the active S3 key", ex);
            return IamCapabilities.unavailable(
                    "This S3 provider did not answer an IAM request: " + rootMessage(ex));
        }
    }

    /** Creates the IAM user, then files its new key in the app's own S3 key store. */
    public StoredKey createUserAndStoreKey(String userName, String createdBy) {
        IamAccessKey key = provider().createUser(userName);
        return storeKey(key, createdBy);
    }

    /** Mints an extra key for an existing IAM user and files it the same way. */
    public StoredKey createAccessKeyAndStore(String userName, String createdBy) {
        IamAccessKey key = provider().createAccessKey(userName);
        return storeKey(key, createdBy);
    }

    /**
     * Persists the key pair as a normal stored S3 key pointing at the same endpoint as the session
     * that created it, with no grants - an admin hands it out from the S3 keys tab. The secret is
     * encrypted at rest and is included only in the immediate creation response, because an IAM
     * provider cannot return it again later.
     */
    private StoredKey storeKey(IamAccessKey key, String createdBy) {
        var settings = settingsService.getEffectiveSettingsOrThrow();
        String name = uniqueName("IAM: " + key.userName());
        var credential = credentialService.create(new S3CredentialService.CredentialForm(
                name,
                endpointFor(settings),
                settings.region(),
                key.accessKeyId(),
                key.secretAccessKey(),
                settings.insecureSkipTlsVerify(),
                true,
                List.of()), createdBy);
        return new StoredKey(key.userName(), key.accessKeyId(), credential.getId(), credential.getName(),
                key.secretAccessKey());
    }

    /**
     * {@code S3CredentialService} requires an endpoint, but a session against real AWS has none -
     * derive the regional S3 endpoint so the stored key is actually usable.
     */
    private static String endpointFor(S3ConnectionSettingsService.EffectiveS3Settings settings) {
        if (settings.endpointUrl() != null && !settings.endpointUrl().isBlank()) {
            return settings.endpointUrl();
        }
        return "https://s3." + settings.region() + ".amazonaws.com";
    }

    /**
     * The app's stored S3 keys that belong to an IAM user on the active backend, resolved to that
     * user. A bucket policy cannot name an access key, only its owner, so this is what lets an
     * admin pick "the key the import job uses" instead of looking up its user ARN. Keys owned by
     * nobody listable (the root key, keys for other endpoints) are left out.
     */
    public List<StoredKeyPrincipal> storedKeyPrincipals() {
        IamProvider provider = provider();
        Map<String, IamUser> owners = new HashMap<>();
        for (IamUser user : provider.listUsers()) {
            try {
                provider.listAccessKeys(user.userName())
                        .forEach(key -> owners.put(key.accessKeyId(), user));
            } catch (RuntimeException ex) {
                // One unreadable user must not hide every other key.
                log.debug("Could not list access keys of IAM user {}", user.userName(), ex);
            }
        }
        List<StoredKeyPrincipal> result = new ArrayList<>();
        for (S3Credential credential : credentialRepository.findAllByOrderByNameAsc()) {
            IamUser owner = owners.get(credential.getAccessKey());
            if (owner != null) {
                result.add(new StoredKeyPrincipal(credential.getId(), credential.getName(),
                        credential.getAccessKey(), owner.userName(), userArn(owner)));
            }
        }
        return result;
    }

    /** Providers without ARNs (RustFS) still match the conventional user principal form. */
    static String userArn(IamUser user) {
        return user.arn() != null && !user.arn().isBlank()
                ? user.arn()
                : "arn:aws:iam:::user/" + user.userName();
    }

    /** Stored key names are unique; suffix until one is free. */
    private String uniqueName(String preferred) {
        if (credentialRepository.findByNameIgnoreCase(preferred).isEmpty()) {
            return preferred;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = preferred + " (" + suffix + ")";
            if (credentialRepository.findByNameIgnoreCase(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a free name for the stored key " + preferred);
    }

    private static String rootMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable && cause.getMessage() != null) {
            return cause.getMessage();
        }
        return throwable.getClass().getSimpleName();
    }

    /**
     * What the API reports back immediately after a key was created. The secret must be presented
     * to the administrator once and must never be returned from a read/list endpoint.
     */
    public record StoredKey(String userName, String accessKeyId, Long credentialId, String credentialName,
                            String secretAccessKey) {
    }

    /** A stored S3 key and the IAM user (principal) that owns it. Never carries the secret. */
    public record StoredKeyPrincipal(Long credentialId, String credentialName, String accessKeyId,
                                     String userName, String arn) {
    }
}
