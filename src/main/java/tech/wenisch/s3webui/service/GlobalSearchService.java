package tech.wenisch.s3webui.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.SessionScope;
import tech.wenisch.s3webui.model.GlobalSearchResult;
import tech.wenisch.s3webui.model.S3ObjectDto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@SessionScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class GlobalSearchService {

    public static final int MIN_QUERY_LENGTH = 2;
    public static final int MAX_QUERY_LENGTH = 200;
    public static final int MAX_RESULT_LIMIT = 50;
    public static final Duration CATALOG_TTL = Duration.ofMinutes(5);

    private final S3Service s3Service;
    private final S3ConnectionSettingsService settingsService;
    private final Clock clock;

    private List<GlobalSearchResult> catalog = List.of();
    private List<String> failedBuckets = List.of();
    private Instant indexedAt;
    private String credentialFingerprint;

    public GlobalSearchService(
            S3Service s3Service,
            S3ConnectionSettingsService settingsService,
            Clock clock) {
        this.s3Service = s3Service;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /** Search calls are serialized per HTTP session so only one catalog rebuild can run at once. */
    public synchronized SearchPage search(String rawQuery, int offset, int limit, boolean forceRefresh) {
        String query = normalizeQuery(rawQuery);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(limit, MAX_RESULT_LIMIT));
        String currentFingerprint = fingerprint(settingsService.getEffectiveSettingsOrThrow());
        Instant now = clock.instant();

        if (forceRefresh || catalogExpired(now) || !currentFingerprint.equals(credentialFingerprint)) {
            rebuild(currentFingerprint, now);
        }

        String foldedQuery = query.toLowerCase(Locale.ROOT);
        List<ScoredResult> matches = catalog.stream()
                .map(result -> score(result, foldedQuery))
                .filter(scored -> scored.score() >= 0)
                .sorted(RESULT_ORDER)
                .toList();

        int total = matches.size();
        int fromIndex = Math.min(safeOffset, total);
        int toIndex = Math.min(fromIndex + safeLimit, total);
        List<GlobalSearchResult> results = matches.subList(fromIndex, toIndex).stream()
                .map(ScoredResult::result)
                .toList();

        return new SearchPage(
                results,
                total,
                toIndex < total,
                indexedAt,
                !failedBuckets.isEmpty(),
                failedBuckets);
    }

    public synchronized void invalidate() {
        catalog = List.of();
        failedBuckets = List.of();
        indexedAt = null;
        credentialFingerprint = null;
    }

    public static String normalizeQuery(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < MIN_QUERY_LENGTH) {
            throw new IllegalArgumentException("Search query must contain at least " + MIN_QUERY_LENGTH + " characters");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Search query must contain at most " + MAX_QUERY_LENGTH + " characters");
        }
        return query;
    }

    private boolean catalogExpired(Instant now) {
        return indexedAt == null
                || now.isBefore(indexedAt)
                || !now.isBefore(indexedAt.plus(CATALOG_TTL));
    }

    private void rebuild(String currentFingerprint, Instant now) {
        List<GlobalSearchResult> files = new ArrayList<>();
        Map<String, GlobalSearchResult> folders = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        for (var bucket : s3Service.listBuckets()) {
            try {
                for (S3ObjectDto object : s3Service.listAllObjects(bucket.getName())) {
                    addObject(bucket.getName(), object, files, folders);
                }
            } catch (RuntimeException exception) {
                failures.add(bucket.getName());
                log.warn("Unable to include bucket '{}' in the global search catalog: {}",
                        bucket.getName(), exception.getMessage());
            }
        }

        List<GlobalSearchResult> rebuilt = new ArrayList<>(folders.values());
        rebuilt.addAll(files);
        catalog = List.copyOf(rebuilt);
        failedBuckets = failures.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        indexedAt = now;
        credentialFingerprint = currentFingerprint;
    }

    private void addObject(
            String bucket,
            S3ObjectDto object,
            List<GlobalSearchResult> files,
            Map<String, GlobalSearchResult> folders) {
        String key = object.getKey();
        if (key == null || key.isEmpty()) {
            return;
        }

        int separator = key.indexOf('/');
        while (separator >= 0) {
            String prefix = key.substring(0, separator + 1);
            String folderName = finalName(prefix);
            if (!folderName.isBlank()) {
                folders.putIfAbsent(bucket + '\0' + prefix, new GlobalSearchResult(
                        GlobalSearchResult.Type.FOLDER,
                        bucket,
                        prefix,
                        folderName,
                        parentPrefix(prefix),
                        null,
                        null));
            }
            separator = key.indexOf('/', separator + 1);
        }

        if (!key.endsWith("/")) {
            files.add(new GlobalSearchResult(
                    GlobalSearchResult.Type.FILE,
                    bucket,
                    key,
                    finalName(key),
                    parentPrefix(key),
                    object.getSize(),
                    object.getLastModified()));
        }
    }

    private static String finalName(String key) {
        String withoutTrailingSlash = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
        int separator = withoutTrailingSlash.lastIndexOf('/');
        return withoutTrailingSlash.substring(separator + 1);
    }

    private static String parentPrefix(String key) {
        String withoutTrailingSlash = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
        int separator = withoutTrailingSlash.lastIndexOf('/');
        return separator < 0 ? "" : withoutTrailingSlash.substring(0, separator + 1);
    }

    private static ScoredResult score(GlobalSearchResult result, String foldedQuery) {
        String name = result.name().toLowerCase(Locale.ROOT);
        if (name.equals(foldedQuery)) {
            return new ScoredResult(result, 0);
        }
        if (name.startsWith(foldedQuery)) {
            return new ScoredResult(result, 1);
        }
        if (name.contains(foldedQuery)) {
            return new ScoredResult(result, 2);
        }
        if (result.key().toLowerCase(Locale.ROOT).contains(foldedQuery)) {
            return new ScoredResult(result, 3);
        }
        return new ScoredResult(result, -1);
    }

    private static final Comparator<ScoredResult> RESULT_ORDER = Comparator
            .comparingInt(ScoredResult::score)
            .thenComparing(scored -> scored.result().bucket(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(scored -> scored.result().bucket())
            .thenComparing(scored -> scored.result().key(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(scored -> scored.result().key())
            .thenComparing(scored -> scored.result().type());

    private static String fingerprint(S3ConnectionSettingsService.EffectiveS3Settings settings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, settings.endpointUrl());
            updateDigest(digest, settings.region());
            updateDigest(digest, settings.accessKey());
            updateDigest(digest, settings.secretKey());
            updateDigest(digest, Boolean.toString(settings.insecureSkipTlsVerify()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        if (value != null) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) 0);
    }

    private record ScoredResult(GlobalSearchResult result, int score) {
    }

    public record SearchPage(
            List<GlobalSearchResult> results,
            int total,
            boolean hasMore,
            Instant indexedAt,
            boolean incomplete,
            List<String> failedBuckets
    ) {
    }
}
