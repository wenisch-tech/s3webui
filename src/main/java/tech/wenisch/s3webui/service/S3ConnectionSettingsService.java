package tech.wenisch.s3webui.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.Serializable;

@Service
public class S3ConnectionSettingsService {

    private static final String SESSION_SETTINGS_KEY = "s3ConnectionSettings";

    private final ObjectProvider<HttpSession> sessionProvider;

    @Value("${s3.access-key:}")
    private String configuredAccessKey;

    @Value("${s3.secret-key:}")
    private String configuredSecretKey;

    @Value("${s3.endpoint-url:}")
    private String configuredEndpointUrl;

    @Value("${s3.region:}")
    private String configuredRegion;

    public S3ConnectionSettingsService(ObjectProvider<HttpSession> sessionProvider) {
        this.sessionProvider = sessionProvider;
    }

    public S3ConfigStatus getStatus() {
        SessionSettings sessionSettings = getSessionSettings(false);

        String accessKeyValue = firstNonBlank(configuredAccessKey, valueFrom(sessionSettings, "accessKey"));
        String secretKeyValue = firstNonBlank(configuredSecretKey, valueFrom(sessionSettings, "secretKey"));
        String endpointUrlValue = firstNonBlank(configuredEndpointUrl, valueFrom(sessionSettings, "endpointUrl"));
        boolean accessKeyMissing = isBlank(configuredAccessKey) && isBlank(valueFrom(sessionSettings, "accessKey"));
        boolean secretKeyMissing = isBlank(configuredSecretKey) && isBlank(valueFrom(sessionSettings, "secretKey"));
        boolean endpointUrlMissing = isBlank(configuredEndpointUrl) && isBlank(valueFrom(sessionSettings, "endpointUrl"));

        String regionValue = firstNonBlank(
                configuredRegion,
                valueFrom(sessionSettings, "region"),
                "us-east-1"
        );
        boolean accessKeyLocked = !isBlank(configuredAccessKey);
        boolean secretKeyLocked = !isBlank(configuredSecretKey);
        boolean endpointUrlLocked = !isBlank(configuredEndpointUrl);
        boolean regionLocked = !isBlank(configuredRegion);

        return new S3ConfigStatus(
                accessKeyMissing || secretKeyMissing || endpointUrlMissing,
                accessKeyMissing,
                secretKeyMissing,
                endpointUrlMissing,
            regionValue,
            accessKeyValue,
            secretKeyLocked ? "********" : secretKeyValue,
            endpointUrlValue,
            accessKeyLocked,
            secretKeyLocked,
            endpointUrlLocked,
            regionLocked
        );
    }

    public EffectiveS3Settings getEffectiveSettingsOrThrow() {
        SessionSettings sessionSettings = getSessionSettings(false);

        String accessKey = firstNonBlank(configuredAccessKey, valueFrom(sessionSettings, "accessKey"));
        String secretKey = firstNonBlank(configuredSecretKey, valueFrom(sessionSettings, "secretKey"));
        String endpointUrl = firstNonBlank(configuredEndpointUrl, valueFrom(sessionSettings, "endpointUrl"));
        String region = firstNonBlank(configuredRegion, valueFrom(sessionSettings, "region"), "us-east-1");

        if (isBlank(accessKey) || isBlank(secretKey) || isBlank(endpointUrl)) {
            throw new MissingS3ConfigurationException("S3 connection is not configured yet");
        }

        return new EffectiveS3Settings(accessKey, secretKey, endpointUrl, region);
    }

    public void saveSessionSettings(SubmittedS3Settings submittedSettings) {
        String accessKey = firstNonBlank(configuredAccessKey, submittedSettings.accessKey());
        String secretKey = firstNonBlank(configuredSecretKey, submittedSettings.secretKey());
        String endpointUrl = firstNonBlank(configuredEndpointUrl, submittedSettings.endpointUrl());

        if (isBlank(accessKey) || isBlank(secretKey) || isBlank(endpointUrl)) {
            throw new MissingS3ConfigurationException("Access key, secret key and endpoint URL are required");
        }

        HttpSession session = resolveSession(true);
        if (session == null) {
            throw new MissingS3ConfigurationException("Unable to access HTTP session to store S3 settings");
        }

        session.setAttribute(
                SESSION_SETTINGS_KEY,
                new SessionSettings(
                        trimToNull(submittedSettings.accessKey()),
                        trimToNull(submittedSettings.secretKey()),
                        trimToNull(submittedSettings.endpointUrl()),
                        trimToNull(submittedSettings.region())
                )
        );
    }

    private SessionSettings getSessionSettings(boolean createSession) {
        HttpSession session = resolveSession(createSession);
        if (session == null) {
            return null;
        }
        Object raw = session.getAttribute(SESSION_SETTINGS_KEY);
        if (raw instanceof SessionSettings settings) {
            return settings;
        }
        return null;
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

    private String valueFrom(SessionSettings settings, String field) {
        if (settings == null) {
            return null;
        }
        return switch (field) {
            case "accessKey" -> settings.accessKey();
            case "secretKey" -> settings.secretKey();
            case "endpointUrl" -> settings.endpointUrl();
            case "region" -> settings.region();
            default -> null;
        };
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            String trimmed = trimToNull(candidate);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return trimToNull(value) == null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record SessionSettings(String accessKey, String secretKey, String endpointUrl, String region)
            implements Serializable {
    }

    public record S3ConfigStatus(
            boolean required,
            boolean accessKeyRequired,
            boolean secretKeyRequired,
            boolean endpointUrlRequired,
            String region,
            String accessKey,
            String secretKey,
            String endpointUrl,
            boolean accessKeyLocked,
            boolean secretKeyLocked,
            boolean endpointUrlLocked,
            boolean regionLocked
    ) {
    }

    public record EffectiveS3Settings(String accessKey, String secretKey, String endpointUrl, String region) {
    }

    public record SubmittedS3Settings(String accessKey, String secretKey, String endpointUrl, String region) {
    }
}
