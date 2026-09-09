package tech.wenisch.s3webui.service;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tech.wenisch.s3webui.model.ResolvedCredential;
import tech.wenisch.s3webui.model.S3CredentialView;

import java.io.Serializable;
import java.util.List;

/**
 * Holds the S3 key the current user picked for this browser session and turns it into the settings
 * an S3 client is built from.
 *
 * <p>The selection lives in the {@link HttpSession}, so two users signed in at the same time browse
 * two different S3 backends without interfering with each other. Only the <em>choice</em> is kept in
 * the session - stored keys are re-read and re-authorised on every request.
 */
@Service
@RequiredArgsConstructor
public class S3ConnectionSettingsService {

    private static final String SESSION_SELECTION_KEY = "s3SessionSelection";

    private final ObjectProvider<HttpSession> sessionProvider;
    private final S3CredentialAccessService credentialAccessService;
    private final AppSettingsService appSettingsService;

    /** The keys the signed-in user may choose from. */
    public List<S3CredentialView> listAvailableCredentials() {
        return credentialAccessService.listAccessible(currentAuthentication());
    }

    public boolean isUserSuppliedCredentialsAllowed() {
        return appSettingsService.isUserSuppliedCredentialsAllowed();
    }

    /** Whether the user still has to pick a key before the UI can show anything. */
    public boolean isSelectionRequired() {
        Selection selection = getSelection();
        if (selection == null) {
            return true;
        }
        try {
            resolve(selection);
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Name of the key in use, or null when nothing is selected. */
    public String getActiveCredentialName() {
        Selection selection = getSelection();
        if (selection == null) {
            return null;
        }
        try {
            return resolve(selection).name();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Id of the key in use; null for own credentials or no selection. */
    public String getActiveCredentialId() {
        Selection selection = getSelection();
        return selection == null ? null : selection.credentialId();
    }

    public EffectiveS3Settings getEffectiveSettingsOrThrow() {
        Selection selection = getSelection();
        if (selection == null) {
            throw new MissingS3ConfigurationException("No S3 key selected for this session yet");
        }

        ResolvedCredential resolved = resolve(selection);
        return new EffectiveS3Settings(
                resolved.accessKey(),
                resolved.secretKey(),
                resolved.endpointUrl(),
                resolved.region(),
                resolved.insecureSkipTlsVerify());
    }

    /** Picks a stored key, or the built-in environment key, for this session. */
    public void selectCredential(String credentialId) {
        if (credentialId == null || credentialId.isBlank()) {
            throw new MissingS3ConfigurationException("No S3 key given");
        }
        // Resolve first so an unauthorised choice never reaches the session.
        credentialAccessService.resolve(currentAuthentication(), credentialId.trim());
        store(new Selection(credentialId.trim(), null, null, null, null, false));
    }

    /** Uses credentials the user typed in, when the administrator allows that. */
    public void selectOwnCredentials(SubmittedS3Settings submitted) {
        if (!appSettingsService.isUserSuppliedCredentialsAllowed()) {
            throw new AccessDeniedException("Using your own S3 credentials has been disabled by an administrator");
        }

        String accessKey = trimToNull(submitted.accessKey());
        String secretKey = trimToNull(submitted.secretKey());
        String endpointUrl = trimToNull(submitted.endpointUrl());
        String region = trimToNull(submitted.region());

        if (accessKey == null || secretKey == null || endpointUrl == null) {
            throw new MissingS3ConfigurationException("Access key, secret key and endpoint URL are required");
        }

        store(new Selection(null, accessKey, secretKey, endpointUrl, region == null ? "us-east-1" : region,
                submitted.insecureSkipTlsVerify()));
    }

    public void clearSelection() {
        HttpSession session = resolveSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_SELECTION_KEY);
        }
    }

    private ResolvedCredential resolve(Selection selection) {
        if (selection.credentialId() != null) {
            return credentialAccessService.resolve(currentAuthentication(), selection.credentialId());
        }
        if (!appSettingsService.isUserSuppliedCredentialsAllowed()) {
            throw new AccessDeniedException("Using your own S3 credentials has been disabled by an administrator");
        }
        return new ResolvedCredential(
                null,
                "Own credentials",
                selection.accessKey(),
                selection.secretKey(),
                selection.endpointUrl(),
                selection.region(),
                selection.insecureSkipTlsVerify());
    }

    private Selection getSelection() {
        HttpSession session = resolveSession(false);
        if (session == null) {
            return null;
        }
        return session.getAttribute(SESSION_SELECTION_KEY) instanceof Selection selection ? selection : null;
    }

    private void store(Selection selection) {
        HttpSession session = resolveSession(true);
        if (session == null) {
            throw new MissingS3ConfigurationException("Unable to access the HTTP session to store the S3 selection");
        }
        session.setAttribute(SESSION_SELECTION_KEY, selection);
    }

    private Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private HttpSession resolveSession(boolean createSession) {
        HttpSession providerSession = sessionProvider.getIfAvailable();
        if (providerSession != null) {
            return providerSession;
        }

        var requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getSession(createSession);
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** What the session remembers: either a key id, or credentials the user typed in. */
    private record Selection(
            String credentialId,
            String accessKey,
            String secretKey,
            String endpointUrl,
            String region,
            boolean insecureSkipTlsVerify
    ) implements Serializable {
    }

    public record EffectiveS3Settings(
            String accessKey,
            String secretKey,
            String endpointUrl,
            String region,
            boolean insecureSkipTlsVerify
    ) {
    }

    public record SubmittedS3Settings(
            String accessKey,
            String secretKey,
            String endpointUrl,
            String region,
            boolean insecureSkipTlsVerify
    ) {
    }
}
