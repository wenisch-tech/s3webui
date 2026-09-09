package tech.wenisch.s3webui.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.client.RestClient;
import tech.wenisch.s3webui.service.UserService;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final OidcProperties oidcProperties;
    private final UserService userService;

    public SecurityConfig(OidcProperties oidcProperties, UserService userService) {
        this.oidcProperties = oidcProperties;
        this.userService = userService;
    }

    @Bean
    public AuthenticationManager authenticationManager(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            "/login", "/error",
                            "/webjars/**", "/css/**", "/js/**", "/img/**",
                            // The bare path covers the aggregate health check; /** covers the
                            // liveness/readiness groups Kubernetes probes should use instead (a
                            // dependency hiccup then only pulls the pod out of rotation via readiness
                            // rather than killing it via liveness) - both 401'd otherwise.
                            "/actuator/health", "/actuator/health/**", "/favicon.ico"
                    ).permitAll();
                    auth.requestMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN");
                    if (oidcProperties.getRequiredRole() != null && !oidcProperties.getRequiredRole().isBlank()) {
                        auth.anyRequest().hasAnyRole(oidcProperties.getRequiredRole(), "ADMIN");
                    } else {
                        auth.anyRequest().authenticated();
                    }
                })
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .failureUrl("/login?error=true")
                        .successHandler(formLoginSuccessHandler())
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout=true")
                        .clearAuthentication(true)
                        .invalidateHttpSession(true)
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
                        .accessDeniedHandler(new ApiAwareAccessDeniedHandler("/access-denied"))
                )
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                );

        if (oidcProperties.isEnabled() && !oidcProperties.getResolvedProviders().isEmpty()) {
            http.oauth2Login(oauth2 -> {
                oauth2.loginPage("/login")
                        .successHandler(new OidcLoginSuccessHandler(userService, oidcProperties.isCreateUsers()))
                        // The plain failureUrl(...) shortcut redirects silently on a technical failure
                        // (state mismatch, TLS/network error talking to the provider) - nothing is ever
                        // logged, which is exactly what made this class of problem invisible before.
                        .failureHandler((request, response, exception) -> {
                            log.warn("OIDC login failed: {}", exception.getMessage(), exception);
                            response.sendRedirect(request.getContextPath() + "/login?error=true");
                        })
                        .userInfoEndpoint(ui -> ui.userAuthoritiesMapper(oidcAuthoritiesMapper()));

                if (oidcProperties.isInsecureSkipTlsVerify()) {
                    oauth2.tokenEndpoint(token -> token.accessTokenResponseClient(insecureTokenResponseClient()));
                }
            });
        }

        return http.build();
    }

    private SavedRequestAwareAuthenticationSuccessHandler formLoginSuccessHandler() {
        SavedRequestAwareAuthenticationSuccessHandler handler =
                new SavedRequestAwareAuthenticationSuccessHandler() {
                    @Override
                    public void onAuthenticationSuccess(jakarta.servlet.http.HttpServletRequest request,
                                                        jakarta.servlet.http.HttpServletResponse response,
                                                        org.springframework.security.core.Authentication auth)
                            throws java.io.IOException, jakarta.servlet.ServletException {
                        userService.updateLastLogin(auth.getName());
                        super.onAuthenticationSuccess(request, response, auth);
                    }
                };
        handler.setDefaultTargetUrl("/");
        return handler;
    }

    @Bean
    @ConditionalOnProperty(name = "oidc.enabled", havingValue = "true")
    public ClientRegistrationRepository clientRegistrationRepository() {
        if (oidcProperties.isInsecureSkipTlsVerify()) {
            enableInsecureTlsForOidc();
        }

        List<OidcProperties.ResolvedProvider> providers = oidcProperties.getResolvedProviders();
        if (providers.isEmpty()) {
            throw new IllegalStateException("OIDC is enabled but no valid OIDC provider is configured.");
        }

        List<ClientRegistration> registrations = new ArrayList<>();
        for (OidcProperties.ResolvedProvider provider : providers) {
            var builder = ClientRegistrations.fromIssuerLocation(provider.issuerUri())
                    .registrationId(provider.registrationId())
                    .clientId(provider.clientId())
                    .scope("openid", "profile", "email")
                    .userNameAttributeName(provider.userNameAttribute())
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}");

            if (provider.clientSecret() != null && !provider.clientSecret().isBlank()) {
                builder.clientSecret(provider.clientSecret());
            }

            registrations.add(builder.build());
        }

        return new InMemoryClientRegistrationRepository(registrations);
    }

    /**
     * Covers the OIDC discovery call ({@link ClientRegistrations#fromIssuerLocation}) and JWKS
     * signature verification (Nimbus's {@code RestOperations}-backed fetcher) - both still go through
     * the JDK's {@link HttpsURLConnection} and respect this global default. The actual token exchange
     * does not; see {@link #insecureTokenResponseClient()}.
     */
    private void enableInsecureTlsForOidc() {
        try {
            SSLContext sslContext = insecureSslContext();
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());

            HostnameVerifier insecureHostnameVerifier = new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            };
            HttpsURLConnection.setDefaultHostnameVerifier(insecureHostnameVerifier);

            log.warn("OIDC_INSECURE_SKIP_TLS_VERIFY is enabled. TLS certificate and hostname verification are disabled for HTTPS connections. Do not use this in production.");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to enable insecure OIDC TLS mode", e);
        }
    }

    /**
     * A token-exchange client that trusts any certificate, for use when {@code OIDC_INSECURE_SKIP_TLS_VERIFY}
     * is set.
     *
     * <p>{@code oauth2Login()}'s default authorization-code token client
     * ({@link RestClientAuthorizationCodeTokenResponseClient}) is built on Apache HttpClient5, which has
     * been a transitive dependency of {@code spring-boot-starter-oauth2-client} since Spring Boot 4.
     * HttpClient5 manages its own {@link SSLContext} and never consults the JDK-wide default installed by
     * {@link #enableInsecureTlsForOidc()} - so that flag silently stopped covering the one HTTPS call that
     * happens right after the identity provider redirects back (POST to its token endpoint), which is
     * exactly the request that used to work before that upgrade. This wires the same trust-all context
     * into that specific client instead of relying on the JDK-wide default.
     */
    private OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> insecureTokenResponseClient() {
        var tokenResponseClient = new RestClientAuthorizationCodeTokenResponseClient();
        tokenResponseClient.setRestClient(insecureRestClient());
        return tokenResponseClient;
    }

    /**
     * The HttpClient5-backed, trust-all {@link RestClient} used for the token exchange. Package-private
     * (rather than private) so {@code SecurityConfigInsecureTlsTest} can exercise the actual HTTP
     * plumbing against a real self-signed endpoint, rather than trusting that it compiles.
     *
     * <p>{@link RestClientAuthorizationCodeTokenResponseClient}'s own no-arg constructor builds a
     * {@code RestClient} with two converters registered - {@link FormHttpMessageConverter} to write the
     * token request, and {@link OAuth2AccessTokenResponseHttpMessageConverter} to parse the response -
     * plus {@link OAuth2ErrorResponseErrorHandler} as the default status handler.
     * {@code setRestClient(RestClient)} does not merge with that default, it replaces it outright: a
     * plain {@code RestClient.builder().build()} falls back to a generic JSON converter that does not
     * know the OAuth2 token response shape and builds an {@code OAuth2AccessTokenResponse} with a null
     * {@code additionalParameters} map, which fails with "additionalParameters cannot be null" on every
     * login. This replicates that same default configuration and only swaps in the trust-all request
     * factory.
     */
    RestClient insecureRestClient() {
        try {
            SSLConnectionSocketFactory socketFactory = SSLConnectionSocketFactoryBuilder.create()
                    .setSslContext(insecureSslContext())
                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .build();
            var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(socketFactory)
                    .build();
            var httpClient = HttpClients.custom().setConnectionManager(connectionManager).build();
            var requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);

            return RestClient.builder()
                    .requestFactory(requestFactory)
                    .configureMessageConverters(converters -> {
                        converters.addCustomConverter(new FormHttpMessageConverter());
                        converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                    })
                    .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                    .build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to build an insecure OIDC token response client", e);
        }
    }

    private SSLContext insecureSslContext() throws GeneralSecurityException {
        TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new SecureRandom());
        return sslContext;
    }

    /**
     * Turns provider claims into authorities credential grants can target: realm and client roles
     * become {@code ROLE_x}, group memberships become {@code GROUP_x}.
     */
    private GrantedAuthoritiesMapper oidcAuthoritiesMapper() {
        return authorities -> {
            Set<GrantedAuthority> mapped = new HashSet<>(authorities);
            authorities.forEach(authority -> {
                if (authority instanceof OidcUserAuthority oidcAuth) {
                    extractClaims(oidcAuth.getIdToken().getClaims(), mapped);
                    if (oidcAuth.getUserInfo() != null) {
                        extractClaims(oidcAuth.getUserInfo().getClaims(), mapped);
                    }
                }
            });
            return mapped;
        };
    }

    private void extractClaims(Map<String, Object> claims, Set<GrantedAuthority> authorities) {
        extractRealmRoles(claims, authorities);
        extractClientRoles(claims, authorities);
        extractGroups(claims, authorities);
    }

    private void extractRealmRoles(Map<String, Object> claims, Set<GrantedAuthority> authorities) {
        if (claims.get("realm_access") instanceof Map<?, ?> realmAccess) {
            addAll(realmAccess.get("roles"), "ROLE_", authorities);
        }
    }

    private void extractClientRoles(Map<String, Object> claims, Set<GrantedAuthority> authorities) {
        if (claims.get("resource_access") instanceof Map<?, ?> resourceAccess) {
            for (Object client : resourceAccess.values()) {
                if (client instanceof Map<?, ?> clientMap) {
                    addAll(clientMap.get("roles"), "ROLE_", authorities);
                }
            }
        }
    }

    private void extractGroups(Map<String, Object> claims, Set<GrantedAuthority> authorities) {
        addAll(claims.get("groups"), "GROUP_", authorities);
    }

    private void addAll(Object rawValues, String prefix, Set<GrantedAuthority> authorities) {
        if (!(rawValues instanceof Collection<?> values)) {
            return;
        }
        values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> !value.isBlank())
                // Keycloak reports group paths as "/team/sub"; grants are written without the slash.
                .map(value -> value.startsWith("/") ? value.substring(1) : value)
                .map(value -> new SimpleGrantedAuthority(prefix + value))
                .forEach(authorities::add);
    }
}
