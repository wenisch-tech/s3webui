package tech.wenisch.s3webui.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${oidc.enabled:false}")
    private boolean oidcEnabled;

    @Value("${oidc.required-role:}")
    private String requiredRole;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        if (!oidcEnabled) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .csrf(AbstractHttpConfigurer::disable);
            return http.build();
        }

        http.authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            "/login", "/error",
                            "/webjars/**", "/css/**", "/js/**",
                            "/actuator/health", "/favicon.ico"
                    ).permitAll();
                    if (requiredRole != null && !requiredRole.isBlank()) {
                        auth.anyRequest().hasRole(requiredRole);
                    } else {
                        auth.anyRequest().authenticated();
                    }
                })
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .userInfoEndpoint(ui -> ui.userAuthoritiesMapper(keycloakAuthoritiesMapper()))
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout=true")
                        .clearAuthentication(true)
                        .invalidateHttpSession(true)
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
                        .accessDeniedPage("/access-denied")
                )
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));

        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "oidc.enabled", havingValue = "true")
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${oidc.client-id}") String clientId,
            @Value("${oidc.client-secret}") String clientSecret,
            @Value("${oidc.issuer-uri}") String issuerUri) {

        ClientRegistration registration = ClientRegistrations.fromIssuerLocation(issuerUri)
                .registrationId("keycloak")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .scope("openid", "profile", "email")
                .userNameAttributeName("preferred_username")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    private GrantedAuthoritiesMapper keycloakAuthoritiesMapper() {
        return authorities -> {
            Set<GrantedAuthority> mapped = new HashSet<>(authorities);
            authorities.forEach(authority -> {
                if (authority instanceof OidcUserAuthority oidcAuth) {
                    extractRealmRoles(oidcAuth.getIdToken().getClaims(), mapped);
                    if (oidcAuth.getUserInfo() != null) {
                        extractRealmRoles(oidcAuth.getUserInfo().getClaims(), mapped);
                    }
                }
            });
            return mapped;
        };
    }

    @SuppressWarnings("unchecked")
    private void extractRealmRoles(Map<String, Object> claims, Set<GrantedAuthority> authorities) {
        Object realmAccess = claims.get("realm_access");
        if (realmAccess instanceof Map<?, ?> ra) {
            Object roles = ra.get("roles");
            if (roles instanceof List<?> roleList) {
                roleList.stream()
                        .filter(String.class::isInstance)
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .forEach(authorities::add);
            }
        }
    }
}
