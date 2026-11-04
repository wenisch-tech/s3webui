package tech.wenisch.s3webui.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tech.wenisch.s3webui.config.S3ClientFactory;
import tech.wenisch.s3webui.model.BucketDto;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService.EffectiveS3Settings;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Hides buckets the selected S3 key cannot browse, when an administrator enabled that.
 *
 * <p>{@code ListBuckets} needs {@code s3:ListAllMyBuckets}, but opening a bucket needs
 * {@code s3:ListBucket} on that bucket, so a key can see buckets it cannot open. Each bucket is
 * probed with a one-key listing - the exact permission browsing needs - and the answer is cached
 * per endpoint, key and bucket. Only an explicit denial hides a bucket: any other failure keeps
 * it, so a flaky backend never makes buckets silently disappear.
 */
@Slf4j
@Service
public class BucketVisibilityService {

    static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_PARALLEL_PROBES = 8;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    /** Lists one key of a bucket; returns normally when browsing is allowed. */
    interface Prober extends AutoCloseable {
        void probe(String bucket);

        @Override
        void close();
    }

    private final AppSettingsService appSettingsService;
    private final S3ConnectionSettingsService connectionSettingsService;
    private final Function<EffectiveS3Settings, Prober> proberFactory;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public BucketVisibilityService(AppSettingsService appSettingsService,
                                   S3ConnectionSettingsService connectionSettingsService,
                                   S3ClientFactory clientFactory) {
        this(appSettingsService, connectionSettingsService,
                settings -> clientProber(clientFactory.create(settings)), Clock.systemUTC());
    }

    BucketVisibilityService(AppSettingsService appSettingsService,
                            S3ConnectionSettingsService connectionSettingsService,
                            Function<EffectiveS3Settings, Prober> proberFactory,
                            Clock clock) {
        this.appSettingsService = appSettingsService;
        this.connectionSettingsService = connectionSettingsService;
        this.proberFactory = proberFactory;
        this.clock = clock;
    }

    /** The buckets the session may browse, in their original order; all of them when the flag is off. */
    public List<BucketDto> filter(List<BucketDto> buckets) {
        if (buckets.isEmpty() || !appSettingsService.areUnbrowsableBucketsHidden()) {
            return buckets;
        }
        EffectiveS3Settings settings = connectionSettingsService.getEffectiveSettingsOrThrow();
        String keyPrefix = settings.endpointUrl() + "\n" + settings.accessKey() + "\n";
        Instant now = clock.instant();

        Map<String, Boolean> browsable = new HashMap<>();
        List<String> unknown = new ArrayList<>();
        for (BucketDto bucket : buckets) {
            Entry entry = cache.get(keyPrefix + bucket.getName());
            if (entry != null && now.isBefore(entry.expiresAt())) {
                browsable.put(bucket.getName(), entry.browsable());
            } else {
                unknown.add(bucket.getName());
            }
        }
        if (!unknown.isEmpty()) {
            Map<String, Boolean> probed = probeAll(settings, unknown);
            Instant expiresAt = now.plus(TTL);
            probed.forEach((bucket, allowed) -> cache.put(keyPrefix + bucket, new Entry(allowed, expiresAt)));
            browsable.putAll(probed);
        }
        return buckets.stream()
                .filter(bucket -> browsable.getOrDefault(bucket.getName(), true))
                .toList();
    }

    /** Forgets every probe result, e.g. after a policy change. */
    public void invalidate() {
        cache.clear();
    }

    private Map<String, Boolean> probeAll(EffectiveS3Settings settings, List<String> buckets) {
        Map<String, Boolean> result = new HashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL_PROBES, buckets.size()));
        try (Prober prober = proberFactory.apply(settings)) {
            Map<String, Future<Boolean>> futures = new HashMap<>();
            for (String bucket : buckets) {
                futures.put(bucket, executor.submit(() -> isBrowsable(prober, bucket)));
            }
            long deadline = System.nanoTime() + PROBE_TIMEOUT.toNanos();
            futures.forEach((bucket, future) -> {
                try {
                    result.put(bucket, future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    result.put(bucket, true);
                } catch (Exception ex) {
                    // Timeouts and unexpected failures fail open: the bucket stays visible.
                    log.debug("Could not probe bucket {}; keeping it visible", bucket, ex);
                    future.cancel(true);
                    result.put(bucket, true);
                }
            });
        } finally {
            executor.shutdownNow();
        }
        return result;
    }

    private static boolean isBrowsable(Prober prober, String bucket) {
        try {
            prober.probe(bucket);
            return true;
        } catch (S3Exception ex) {
            boolean denied = ex.statusCode() == 403
                    || (ex.awsErrorDetails() != null && "NoSuchBucket".equals(ex.awsErrorDetails().errorCode()));
            if (!denied) {
                log.debug("Probe of bucket {} failed with {}; keeping it visible", bucket, ex.statusCode());
            }
            return !denied;
        }
    }

    private static Prober clientProber(S3Client client) {
        return new Prober() {
            @Override
            public void probe(String bucket) {
                client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).maxKeys(1).build());
            }

            @Override
            public void close() {
                client.close();
            }
        };
    }

    private record Entry(boolean browsable, Instant expiresAt) {
    }
}
