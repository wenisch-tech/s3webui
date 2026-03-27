package tech.wenisch.s3webui.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

@Slf4j
@Configuration
public class S3Config {

    @Value("${s3.access-key}")
    private String accessKey;

    @Value("${s3.secret-key}")
    private String secretKey;

    @Value("${s3.endpoint-url}")
    private String endpointUrl;

    @Value("${s3.region:us-east-1}")
    private String region;

    @Value("${s3.insecure-skip-tls-verify:false}")
    private boolean s3InsecureSkipTlsVerify;

    @Bean
    public S3Client s3Client() {
        if (s3InsecureSkipTlsVerify) {
            enableInsecureTlsForS3();
        }

        var credentials = AwsBasicCredentials.create(accessKey, secretKey);
        var builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder()
                        .checksumValidationEnabled(false)
                        .pathStyleAccessEnabled(true)
                        .build());

        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }

        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        if (s3InsecureSkipTlsVerify) {
            enableInsecureTlsForS3();
        }

        var credentials = AwsBasicCredentials.create(accessKey, secretKey);
        var builder = S3Presigner.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build());

        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }

        return builder.build();
    }

    private void enableInsecureTlsForS3() {
        try {
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
            // AWS SDK URLConnection client may use the JVM default SSLContext directly.
            SSLContext.setDefault(sslContext);
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());

            HostnameVerifier insecureHostnameVerifier = new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            };
            HttpsURLConnection.setDefaultHostnameVerifier(insecureHostnameVerifier);

            log.warn("S3_INSECURE_SKIP_TLS_VERIFY is enabled. TLS certificate and hostname verification are disabled for HTTPS connections. Do not use this in production.");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to enable insecure S3 TLS mode", e);
        }
    }
}
