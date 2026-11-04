package tech.wenisch.s3webui.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService.EffectiveS3Settings;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds an {@link S3Client} for a set of connection settings. The request-scoped client bean uses
 * it, and so does anything that has to talk to S3 off the request thread, where the scoped proxy
 * cannot be resolved.
 */
@Slf4j
@Component
public class S3ClientFactory {

    private static final AtomicBoolean INSECURE_TLS_WARNING_LOGGED = new AtomicBoolean(false);

    public S3Client create(EffectiveS3Settings settings) {
        var credentials = AwsBasicCredentials.create(settings.accessKey(), settings.secretKey());
        UrlConnectionHttpClient.Builder httpClientBuilder = UrlConnectionHttpClient.builder();
        if (settings.insecureSkipTlsVerify()) {
            httpClientBuilder.tlsTrustManagersProvider(S3ClientFactory::insecureTrustManagers);
            if (INSECURE_TLS_WARNING_LOGGED.compareAndSet(false, true)) {
                log.warn("TLS certificate verification is disabled for at least one S3 key. Do not use this in production.");
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

    private static TrustManager[] insecureTrustManagers() {
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
