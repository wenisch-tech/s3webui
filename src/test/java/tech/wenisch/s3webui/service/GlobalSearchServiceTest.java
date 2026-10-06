package tech.wenisch.s3webui.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.s3webui.model.BucketDto;
import tech.wenisch.s3webui.model.GlobalSearchResult;
import tech.wenisch.s3webui.model.S3ObjectDto;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalSearchServiceTest {

    private S3Service s3Service;
    private S3ConnectionSettingsService settingsService;
    private MutableClock clock;
    private GlobalSearchService searchService;

    @BeforeEach
    void setUp() {
        s3Service = mock(S3Service.class);
        settingsService = mock(S3ConnectionSettingsService.class);
        clock = new MutableClock(Instant.parse("2026-10-06T09:00:00Z"));
        searchService = new GlobalSearchService(s3Service, settingsService, clock);
        when(settingsService.getEffectiveSettingsOrThrow()).thenReturn(settings("access-a", "secret-a"));
    }

    @Test
    void derivesAndDeduplicatesFoldersWithoutReturningFolderMarkersAsFiles() {
        when(s3Service.listBuckets()).thenReturn(List.of(bucket("bucket-a")));
        when(s3Service.listAllObjects("bucket-a")).thenReturn(List.of(
                object("docs/", 0),
                object("docs/a.txt", 10),
                object("docs/nested/b.txt", 20)));

        var page = searchService.search("docs", 0, 50, false);

        assertEquals(1, page.results().stream()
                .filter(result -> result.type() == GlobalSearchResult.Type.FOLDER)
                .filter(result -> result.key().equals("docs/"))
                .count());
        assertFalse(page.results().stream().anyMatch(result ->
                result.type() == GlobalSearchResult.Type.FILE && result.key().equals("docs/")));
    }

    @Test
    void ranksExactPrefixSubstringAndPathOnlyMatchesInThatOrder() {
        when(s3Service.listBuckets()).thenReturn(List.of(bucket("bucket-a")));
        when(s3Service.listAllObjects("bucket-a")).thenReturn(List.of(
                object("docs/report", 1),
                object("docs/reporting.txt", 1),
                object("docs/my-report.txt", 1),
                object("report-path/other.bin", 1)));

        var page = searchService.search("report", 0, 50, false);
        List<String> fileNames = page.results().stream()
                .filter(result -> result.type() == GlobalSearchResult.Type.FILE)
                .map(GlobalSearchResult::name)
                .toList();

        assertEquals(List.of("report", "reporting.txt", "my-report.txt", "other.bin"), fileNames);
    }

    @Test
    void paginatesRankedMatchesAndReportsMoreResults() {
        when(s3Service.listBuckets()).thenReturn(List.of(bucket("bucket-a")));
        when(s3Service.listAllObjects("bucket-a")).thenReturn(List.of(
                object("match-a", 1), object("match-b", 1), object("match-c", 1)));

        var first = searchService.search("match", 0, 2, false);
        var second = searchService.search("match", 2, 2, false);

        assertEquals(3, first.total());
        assertEquals(List.of("match-a", "match-b"), first.results().stream().map(GlobalSearchResult::name).toList());
        assertTrue(first.hasMore());
        assertEquals(List.of("match-c"), second.results().stream().map(GlobalSearchResult::name).toList());
        assertFalse(second.hasMore());
    }

    @Test
    void reusesCatalogUntilForcedInvalidatedExpiredOrCredentialChanges() {
        when(s3Service.listBuckets()).thenReturn(List.of(bucket("bucket-a")));
        when(s3Service.listAllObjects("bucket-a")).thenReturn(List.of(object("match.txt", 1)));

        searchService.search("match", 0, 5, false);
        searchService.search("match", 0, 5, false);
        verify(s3Service).listBuckets();

        searchService.search("match", 0, 5, true);
        searchService.invalidate();
        searchService.search("match", 0, 5, false);
        clock.advance(GlobalSearchService.CATALOG_TTL);
        searchService.search("match", 0, 5, false);
        when(settingsService.getEffectiveSettingsOrThrow()).thenReturn(settings("access-b", "secret-b"));
        searchService.search("match", 0, 5, false);

        verify(s3Service, times(5)).listBuckets();
    }

    @Test
    void keepsSuccessfulBucketsAndReportsFailures() {
        when(s3Service.listBuckets()).thenReturn(List.of(bucket("broken"), bucket("working")));
        when(s3Service.listAllObjects("broken")).thenThrow(new RuntimeException("Access denied"));
        when(s3Service.listAllObjects("working")).thenReturn(List.of(object("match.txt", 1)));

        var page = searchService.search("match", 0, 5, false);

        assertTrue(page.incomplete());
        assertEquals(List.of("broken"), page.failedBuckets());
        assertEquals(List.of("working"), page.results().stream().map(GlobalSearchResult::bucket).toList());
    }

    @Test
    void validatesAndNormalizesQueries() {
        assertEquals("report", GlobalSearchService.normalizeQuery("  report  "));
        assertThrows(IllegalArgumentException.class, () -> GlobalSearchService.normalizeQuery("x"));
        assertThrows(IllegalArgumentException.class,
                () -> GlobalSearchService.normalizeQuery("x".repeat(GlobalSearchService.MAX_QUERY_LENGTH + 1)));
    }

    private static BucketDto bucket(String name) {
        return BucketDto.builder().name(name).build();
    }

    private static S3ObjectDto object(String key, long size) {
        return S3ObjectDto.builder().key(key).name(key).size(size).build();
    }

    private static S3ConnectionSettingsService.EffectiveS3Settings settings(String accessKey, String secretKey) {
        return new S3ConnectionSettingsService.EffectiveS3Settings(
                accessKey, secretKey, "https://s3.example.com", "eu-central-1", false);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
