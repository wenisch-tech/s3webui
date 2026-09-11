package tech.wenisch.s3webui.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.annotation.RequestScope;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService;
import tech.wenisch.s3webui.service.iam.AwsIamProvider;
import tech.wenisch.s3webui.service.iam.IamProvider;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * Builds the IAM client from the S3 key selected for the current session, mirroring
 * {@link S3Config}. Absent when {@code iam.enabled} is explicitly false, so a deployment that
 * does not want the feature never even constructs the client.
 */
@Configuration
@ConditionalOnProperty(name = "iam.enabled", havingValue = "true", matchIfMissing = true)
public class IamConfig {

    /** IAM is a global service; a real AWS endpoint only answers under this pseudo-region. */
    private static final Region GLOBAL = Region.AWS_GLOBAL;

    /**
     * Hard ceiling on a single IAM call. A backend with no IAM API may simply never answer rather
     * than refusing - MinIO leaves the request hanging - and the SDK would otherwise retry four
     * times at thirty seconds each, freezing the capability probe for two minutes. IAM is a
     * control-plane API; a healthy one answers in well under a second.
     */
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    @RequestScope
    public IamClient iamClient(S3ConnectionSettingsService settingsService) {
        var settings = settingsService.getEffectiveSettingsOrThrow();
        var credentials = AwsBasicCredentials.create(settings.accessKey(), settings.secretKey());

        UrlConnectionHttpClient.Builder httpClientBuilder = UrlConnectionHttpClient.builder()
                .connectionTimeout(CONNECTION_TIMEOUT)
                .socketTimeout(SOCKET_TIMEOUT);
        if (settings.insecureSkipTlsVerify()) {
            httpClientBuilder.tlsTrustManagersProvider(IamConfig::insecureTrustManagers);
        }

        var builder = IamClient.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .httpClientBuilder(httpClientBuilder)
                .overrideConfiguration(override -> override
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .apiCallAttemptTimeout(SOCKET_TIMEOUT));

        if (settings.endpointUrl() != null && !settings.endpointUrl().isBlank()) {
            // A custom endpoint (LocalStack, an IAM-capable gateway) signs under the key's own
            // region; only real AWS insists on aws-global.
            builder.endpointOverride(URI.create(settings.endpointUrl()))
                    .region(Region.of(settings.region()));
        } else {
            builder.region(GLOBAL);
        }

        return builder.build();
    }

    @Bean
    @RequestScope
    public IamProvider iamProvider(IamClient iamClient) {
        return new AwsIamProvider(iamClient);
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
