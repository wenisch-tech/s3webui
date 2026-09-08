package tech.wenisch.s3webui.config;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tech.wenisch.s3webui.service.UserService;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Proves {@link SecurityConfig#insecureRestClient()} actually bypasses TLS trust validation against a
 * real self-signed certificate - and that Spring's stock {@link RestClient} genuinely does not.
 *
 * <p>This is the concrete regression the class-level javadoc on {@code insecureTokenResponseClient()}
 * describes: after the Spring Boot 4 upgrade, {@code oauth2Login()}'s default token-exchange client
 * moved to Apache HttpClient5, which manages its own trust store and never consulted the JDK-wide
 * {@link javax.net.ssl.HttpsURLConnection} default that {@code OIDC_INSECURE_SKIP_TLS_VERIFY} used to
 * rely on - so that flag silently stopped protecting the one HTTPS call that happens right after the
 * identity provider redirects back. A compile-only check would not have caught that; only exercising
 * real HTTP traffic against an untrusted certificate does.
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
            byte[] body = "ok".getBytes();
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
    void insecureRestClientTrustsTheSameCertificate() {
        SecurityConfig securityConfig = new SecurityConfig(new OidcProperties(), mock(UserService.class));

        RestClient insecureClient = securityConfig.insecureRestClient();
        String body = insecureClient.get().uri(baseUrl).retrieve().body(String.class);

        assertThat(body).isEqualTo("ok");
    }
}
