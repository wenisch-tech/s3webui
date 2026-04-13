package tech.wenisch.s3webui.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.annotation.RequestScope;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Configuration
public class S3Config {

    private static final AtomicBoolean INSECURE_TLS_WARNING_LOGGED = new AtomicBoolean(false);

    @Value("${s3.insecure-skip-tls-verify:false}")
    private boolean s3InsecureSkipTlsVerify;

    @Bean
    @RequestScope
    public S3Client s3Client(S3ConnectionSettingsService settingsService) {
        var settings = settingsService.getEffectiveSettingsOrThrow();
        var credentials = AwsBasicCredentials.create(settings.accessKey(), settings.secretKey());
        UrlConnectionHttpClient.Builder httpClientBuilder = UrlConnectionHttpClient.builder();
        if (s3InsecureSkipTlsVerify) {
            httpClientBuilder.tlsTrustManagersProvider(this::insecureTrustManagers);
            if (INSECURE_TLS_WARNING_LOGGED.compareAndSet(false, true)) {
                log.warn("S3_INSECURE_SKIP_TLS_VERIFY is enabled. TLS certificate verification is disabled for S3 HTTP client. Do not use this in production.");
            }
        }

        var builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(settings.region()))
                .httpClientBuilder(httpClientBuilder)
                .serviceConfiguration(S3Configuration.builder()
                        .checksumValidationEnabled(false)
                        .pathStyleAccessEnabled(true)
                        .build());

        if (settings.endpointUrl() != null && !settings.endpointUrl().isBlank()) {
            builder.endpointOverride(URI.create(settings.endpointUrl()));
        }

        return builder.build();
    }

    @Bean
    @RequestScope
    public S3Presigner s3Presigner(S3ConnectionSettingsService settingsService) {
        var settings = settingsService.getEffectiveSettingsOrThrow();
        var credentials = AwsBasicCredentials.create(settings.accessKey(), settings.secretKey());
        var builder = S3Presigner.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(settings.region()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build());

        if (settings.endpointUrl() != null && !settings.endpointUrl().isBlank()) {
            builder.endpointOverride(URI.create(settings.endpointUrl()));
        }

        return builder.build();
    }

    private TrustManager[] insecureTrustManagers() {
        return new TrustManager[]{new X509TrustManager() {
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
    }
}
