package tech.wenisch.s3webui.service;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tech.wenisch.s3webui.model.BucketDto;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService.EffectiveS3Settings;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BucketVisibilityServiceTest {

    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final S3ConnectionSettingsService connection = mock(S3ConnectionSettingsService.class);
    private final List<String> probed = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-04T12:00:00Z"));

    /** Per bucket: null = browsable, otherwise the exception the probe throws. */
    private Map<String, RuntimeException> outcomes = Map.of();

    private final BucketVisibilityService service = new BucketVisibilityService(
            appSettings, connection,
            settings -> new BucketVisibilityService.Prober() {
                @Override
                public void probe(String bucket) {
                    probed.add(settings.accessKey() + ":" + bucket);
                    RuntimeException failure = outcomes.get(bucket);
                    if (failure != null) {
                        throw failure;
                    }
                }

                @Override
                public void close() {
                }
            },
            new Clock() {
                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now.get();
                }
            });

    private void hideEnabled(String accessKey) {
        when(appSettings.areUnbrowsableBucketsHidden()).thenReturn(true);
        when(connection.getEffectiveSettingsOrThrow()).thenReturn(
                new EffectiveS3Settings(accessKey, "secret", "http://s3.local", "us-east-1", false));
    }

    private static List<BucketDto> buckets(String... names) {
        return java.util.Arrays.stream(names).map(name -> BucketDto.builder().name(name).build()).toList();
    }

    private static List<String> names(List<BucketDto> buckets) {
        return buckets.stream().map(BucketDto::getName).toList();
    }

    private static S3Exception s3Error(int status, String code) {
        return (S3Exception) S3Exception.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
                .build();
    }

    @Test
    void withTheFlagOffEveryBucketIsShownAndNothingIsProbed() {
        when(appSettings.areUnbrowsableBucketsHidden()).thenReturn(false);

        assertThat(names(service.filter(buckets("a", "b")))).containsExactly("a", "b");
        assertThat(probed).isEmpty();
    }

    @Test
    void onlyExplicitDenialsHideABucketAndOrderIsKept() {
        hideEnabled("AKIA");
        outcomes = Map.of(
                "denied", s3Error(403, "AccessDenied"),
                "gone", s3Error(404, "NoSuchBucket"),
                "flaky", s3Error(500, "InternalError"),
                "network", new IllegalStateException("connection reset"));

        var visible = service.filter(buckets("open", "denied", "flaky", "gone", "network", "also-open"));

        assertThat(names(visible)).containsExactly("open", "flaky", "network", "also-open");
    }

    @Test
    void resultsAreCachedPerKeyUntilTheyExpireOrAreInvalidated() {
        hideEnabled("AKIA");
        outcomes = Map.of("denied", s3Error(403, "AccessDenied"));

        service.filter(buckets("open", "denied"));
        service.filter(buckets("open", "denied"));
        assertThat(probed).hasSize(2);

        // Another key shares nothing with the first one.
        hideEnabled("OTHER");
        service.filter(buckets("open"));
        assertThat(probed).contains("OTHER:open").hasSize(3);

        hideEnabled("AKIA");
        now.set(now.get().plus(BucketVisibilityService.TTL));
        service.filter(buckets("open"));
        assertThat(probed).hasSize(4);

        service.invalidate();
        service.filter(buckets("open"));
        assertThat(probed).hasSize(5);
    }

    @Test
    void anEmptyListNeedsNoConnection() {
        when(appSettings.areUnbrowsableBucketsHidden()).thenReturn(true);

        assertThat(service.filter(List.of())).isEmpty();
        assertThat(probed).isEmpty();
    }
}
