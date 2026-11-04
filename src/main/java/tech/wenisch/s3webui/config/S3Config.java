package tech.wenisch.s3webui.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.annotation.RequestScope;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {

    @Bean
    @RequestScope
    public S3Client s3Client(S3ConnectionSettingsService settingsService, S3ClientFactory clientFactory) {
        return clientFactory.create(settingsService.getEffectiveSettingsOrThrow());
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
}
