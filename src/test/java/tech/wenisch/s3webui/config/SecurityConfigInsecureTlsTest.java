package tech.wenisch.s3webui.config;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tech.wenisch.s3webui.service.UserService;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Proves {@link SecurityConfig#insecureRestClient()} actually bypasses TLS trust validation against a
 * real self-signed certificate - and that Spring's stock {@link RestClient} genuinely does not - and
 * that the client can complete a real OAuth2 token exchange against a server on that certificate.
 *
 * <p>This is the concrete regression the class-level javadoc on {@code insecureTokenResponseClient()}
 * describes: after the Spring Boot 4 upgrade, {@code oauth2Login()}'s default token-exchange client
 * moved to Apache HttpClient5, which manages its own trust store and never consulted the JDK-wide
 * {@link javax.net.ssl.HttpsURLConnection} default that {@code OIDC_INSECURE_SKIP_TLS_VERIFY} used to
 * rely on - so that flag silently stopped protecting the one HTTPS call that happens right after the
 * identity provider redirects back. A compile-only check would not have caught that; only exercising
 * real HTTP traffic against an untrusted certificate does. Nor would it have caught the follow-up bug
 * ({@code insecureRestClientCompletesARealTokenExchange}): a {@code RestClient} built without
 * replicating {@link RestClientAuthorizationCodeTokenResponseClient}'s own message converters fails
 * every login with "additionalParameters cannot be null", because the generic JSON converter that
 * takes over doesn't know the OAuth2 token response shape.
 */
class SecurityConfigInsecureTlsTest {

    private static HttpsServer server;
    private static String baseUrl;

    @BeforeAll
    static void startSelfSignedServer(@TempDir Path tempDir) throws Exception {
        Path keystore = tempDir.resolve("selfsigned.p12");
        Process keytool = new ProcessBuilder(
                javaHomeKeytool(),
                "-genkeypair", "-alias", "test",
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1",
                "-keystore", keystore.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit",
                "-dname", "CN=127.0.0.1")
                .redirectErrorStream(true)
                .start();
        String output = new String(keytool.getInputStream().readAllBytes());
        if (keytool.waitFor() != 0) {
            throw new IllegalStateException("keytool failed to generate a test certificate: " + output);
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(keystore.toFile())) {
            keyStore.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, "changeit".toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagerFactory.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/token", exchange -> {
            // A minimal, spec-shaped token response - enough for OAuth2AccessTokenResponseHttpMessageConverter
            // to build a real OAuth2AccessTokenResponse from.
            byte[] body = """
                    {"access_token":"test-access-token","token_type":"Bearer","expires_in":3600}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "https://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String javaHomeKeytool() {
        String home = System.getProperty("java.home");
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
        return Path.of(home, "bin", exe).toString();
    }

    @Test
    void aStockRestClientRejectsTheSelfSignedCertificate() {
        RestClient client = RestClient.create();

        assertThatThrownBy(() -> client.get().uri(baseUrl).retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void insecureRestClientCompletesARealTokenExchange() {
        SecurityConfig securityConfig = new SecurityConfig(new OidcProperties(), mock(UserService.class));
        var tokenResponseClient = new RestClientAuthorizationCodeTokenResponseClient();
        tokenResponseClient.setRestClient(securityConfig.insecureRestClient());

        OAuth2AccessTokenResponse response = tokenResponseClient.getTokenResponse(authorizationCodeGrantRequest());

        assertThat(response.getAccessToken().getTokenValue()).isEqualTo("test-access-token");
        assertThat(response.getAdditionalParameters()).isNotNull();
    }

    private OAuth2AuthorizationCodeGrantRequest authorizationCodeGrantRequest() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("test")
                .clientId("client-id")
                .clientSecret("client-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example.com/callback")
                .authorizationUri("https://provider.example.com/authorize")
                .tokenUri(baseUrl + "token")
                .build();

        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(registration.getRedirectUri())
                .state("test-state")
                .build();

        OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse.success("test-code")
                .redirectUri(registration.getRedirectUri())
                .state("test-state")
                .build();

        return new OAuth2AuthorizationCodeGrantRequest(
                registration, new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse));
    }
}
