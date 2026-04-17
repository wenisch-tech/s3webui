package tech.wenisch.s3webui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "oidc")
public class OidcProperties {

    private boolean enabled;
    private String requiredRole = "";
    private boolean insecureSkipTlsVerify;
    private String providerName = "Single Sign-On";
    private String clientId = "";
    private String clientSecret = "";
    private String issuerUri = "";
    private List<Provider> providers = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRequiredRole() {
        return requiredRole;
    }

    public void setRequiredRole(String requiredRole) {
        this.requiredRole = requiredRole;
    }

    public boolean isInsecureSkipTlsVerify() {
        return insecureSkipTlsVerify;
    }

    public void setInsecureSkipTlsVerify(boolean insecureSkipTlsVerify) {
        this.insecureSkipTlsVerify = insecureSkipTlsVerify;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public List<Provider> getProviders() {
        return providers;
    }

    public void setProviders(List<Provider> providers) {
        this.providers = providers != null ? providers : new ArrayList<>();
    }

    public List<ResolvedProvider> getResolvedProviders() {
        List<ResolvedProvider> resolved = new ArrayList<>();
        int fallbackIndex = 1;
        for (Provider provider : providers) {
            if (!provider.isConfigured()) {
                continue;
            }
            resolved.add(new ResolvedProvider(
                    provider.resolveName(fallbackIndex),
                    provider.resolveRegistrationId(fallbackIndex),
                    provider.clientId,
                    provider.clientSecret,
                    provider.issuerUri,
                    provider.resolveUserNameAttribute()
            ));
            fallbackIndex++;
        }

        if (!resolved.isEmpty()) {
            validateUniqueRegistrationIds(resolved);
            return List.copyOf(resolved);
        }

        if (isLegacyConfigured()) {
            return List.of(new ResolvedProvider(
                    hasText(providerName) ? providerName.trim() : "Single Sign-On",
                    "keycloak",
                    clientId.trim(),
                    trimToEmpty(clientSecret),
                    issuerUri.trim(),
                    "preferred_username"
            ));
        }

        return List.of();
    }

    public List<LoginProviderView> getLoginProviders() {
        return getResolvedProviders().stream()
                .map(provider -> new LoginProviderView(
                        provider.name(),
                        provider.registrationId(),
                        "/oauth2/authorization/" + provider.registrationId()))
                .toList();
    }

    private boolean isLegacyConfigured() {
        return hasText(clientId) && hasText(issuerUri);
    }

    private void validateUniqueRegistrationIds(List<ResolvedProvider> resolvedProviders) {
        Set<String> registrationIds = new LinkedHashSet<>();
        for (ResolvedProvider provider : resolvedProviders) {
            if (!registrationIds.add(provider.registrationId())) {
                throw new IllegalStateException("Duplicate OIDC registrationId configured: " + provider.registrationId());
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public static class Provider {

        private String name = "";
        private String registrationId = "";
        private String clientId = "";
        private String clientSecret = "";
        private String issuerUri = "";
        private String userNameAttribute = "preferred_username";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getRegistrationId() {
            return registrationId;
        }

        public void setRegistrationId(String registrationId) {
            this.registrationId = registrationId;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getUserNameAttribute() {
            return userNameAttribute;
        }

        public void setUserNameAttribute(String userNameAttribute) {
            this.userNameAttribute = userNameAttribute;
        }

        private boolean isConfigured() {
            return hasText(clientId) && hasText(issuerUri);
        }

        private String resolveName(int fallbackIndex) {
            return hasText(name) ? name.trim() : "Provider " + fallbackIndex;
        }

        private String resolveRegistrationId(int fallbackIndex) {
            if (hasText(registrationId)) {
                return sanitizeRegistrationId(registrationId);
            }
            if (hasText(name)) {
                return sanitizeRegistrationId(name);
            }
            return "provider-" + fallbackIndex;
        }

        private String resolveUserNameAttribute() {
            return hasText(userNameAttribute) ? userNameAttribute.trim() : "preferred_username";
        }

        private String sanitizeRegistrationId(String rawValue) {
            String sanitized = rawValue.trim().toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            return sanitized.isBlank() ? "provider-1" : sanitized;
        }
    }

    public record ResolvedProvider(
            String name,
            String registrationId,
            String clientId,
            String clientSecret,
            String issuerUri,
            String userNameAttribute
    ) {
    }

    public record LoginProviderView(String name, String registrationId, String authorizationUrl) {
    }
}