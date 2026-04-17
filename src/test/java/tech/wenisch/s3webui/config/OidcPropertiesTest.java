package tech.wenisch.s3webui.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OidcPropertiesTest {

    @Test
    void fallsBackToLegacySingleProviderConfiguration() {
        OidcProperties properties = new OidcProperties();
        properties.setProviderName("Company SSO");
        properties.setClientId("s3webui");
        properties.setClientSecret("secret");
        properties.setIssuerUri("https://auth.example.com/realms/main");

        List<OidcProperties.LoginProviderView> providers = properties.getLoginProviders();

        assertEquals(1, providers.size());
        assertEquals("Company SSO", providers.get(0).name());
        assertEquals("/oauth2/authorization/keycloak", providers.get(0).authorizationUrl());
    }

    @Test
    void resolvesMultipleConfiguredProviders() {
        OidcProperties.Provider first = new OidcProperties.Provider();
        first.setName("Internal SSO");
        first.setClientId("internal-client");
        first.setIssuerUri("https://auth.example.com/realms/internal");

        OidcProperties.Provider second = new OidcProperties.Provider();
        second.setName("Partner Login");
        second.setClientId("partner-client");
        second.setIssuerUri("https://partner.example.com/realms/partner");

        OidcProperties properties = new OidcProperties();
        properties.setProviders(List.of(first, second));

        List<OidcProperties.LoginProviderView> providers = properties.getLoginProviders();

        assertEquals(2, providers.size());
        assertEquals("Internal SSO", providers.get(0).name());
        assertEquals("/oauth2/authorization/internal-sso", providers.get(0).authorizationUrl());
        assertEquals("Partner Login", providers.get(1).name());
        assertEquals("/oauth2/authorization/partner-login", providers.get(1).authorizationUrl());
    }

    @Test
    void rejectsDuplicateResolvedRegistrationIds() {
        OidcProperties.Provider first = new OidcProperties.Provider();
        first.setName("Company SSO");
        first.setClientId("internal-client");
        first.setIssuerUri("https://auth.example.com/realms/internal");

        OidcProperties.Provider second = new OidcProperties.Provider();
        second.setRegistrationId("company-sso");
        second.setClientId("partner-client");
        second.setIssuerUri("https://partner.example.com/realms/partner");

        OidcProperties properties = new OidcProperties();
        properties.setProviders(List.of(first, second));

        assertThrows(IllegalStateException.class, properties::getResolvedProviders);
    }
}