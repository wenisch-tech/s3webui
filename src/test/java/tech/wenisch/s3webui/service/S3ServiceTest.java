package tech.wenisch.s3webui.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.Transition;
import software.amazon.awssdk.services.s3.model.TransitionStorageClass;
import tech.wenisch.s3webui.model.LifecycleRuleDto;
import tech.wenisch.s3webui.model.ObjectVersionDto;
import java.time.Instant;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.DeleteBucketLifecycleRequest;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.NoncurrentVersionExpiration;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.GetBucketCorsResponse;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tech.wenisch.s3webui.model.CompleteMultipartRequest;
import tech.wenisch.s3webui.model.CorsRuleDto;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ServiceTest {

    @Test
    void createBucketThrowsDuplicateBucketExceptionWhenNameAlreadyExists() {
        S3Client s3Client = mock(S3Client.class);
        S3Presigner s3Presigner = mock(S3Presigner.class);
        S3Service s3Service = new S3Service(s3Client, s3Presigner);

        when(s3Client.listBuckets()).thenReturn(ListBucketsResponse.builder()
                .buckets(Bucket.builder().name("test").build())
                .build());

        DuplicateBucketException exception = assertThrows(
                DuplicateBucketException.class,
                () -> s3Service.createBucket("test"));

        assertEquals("Bucket 'test' already exists", exception.getMessage());
        verify(s3Client, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void uploadPartPreservesQuotedEtag() {
        S3Client s3Client = mock(S3Client.class);
        S3Presigner s3Presigner = mock(S3Presigner.class);
        S3Service s3Service = new S3Service(s3Client, s3Presigner);

        when(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("\"etag-1\"").build());

        String eTag = s3Service.uploadPart("bucket-a", "file.bin", "upload-1", 1, new byte[] {1, 2, 3});

        assertEquals("\"etag-1\"", eTag);
    }

    @Test
        void completeMultipartUploadUsesListedEtagsSortedByPartNumber() {
        S3Client s3Client = mock(S3Client.class);
        S3Presigner s3Presigner = mock(S3Presigner.class);
        S3Service s3Service = new S3Service(s3Client, s3Presigner);

        when(s3Client.listParts(any(ListPartsRequest.class)))
            .thenReturn(ListPartsResponse.builder()
                .isTruncated(false)
                .parts(
                    Part.builder().partNumber(2).eTag("\"server-etag-2\"").build(),
                    Part.builder().partNumber(1).eTag("\"server-etag-1\"").build()
                )
                .build());

        when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenReturn(CompleteMultipartUploadResponse.builder().build());

        CompleteMultipartRequest.PartETag part2 = new CompleteMultipartRequest.PartETag();
        part2.setPartNumber(2);
        part2.setETag("\"etag-2\"");

        CompleteMultipartRequest.PartETag part1 = new CompleteMultipartRequest.PartETag();
        part1.setPartNumber(1);
        part1.setETag("\"etag-1\"");

        s3Service.completeMultipartUpload("bucket-a", "file.bin", "upload-1", List.of(part2, part1));

        ArgumentCaptor<CompleteMultipartUploadRequest> requestCaptor = ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(s3Client).listParts(any(ListPartsRequest.class));
        verify(s3Client).completeMultipartUpload(requestCaptor.capture());

        List<CompletedPart> completedParts = requestCaptor.getValue().multipartUpload().parts();
        assertEquals(1, completedParts.get(0).partNumber());
        assertEquals("\"server-etag-1\"", completedParts.get(0).eTag());
        assertEquals(2, completedParts.get(1).partNumber());
        assertEquals("\"server-etag-2\"", completedParts.get(1).eTag());
    }

    // ── Bucket policy ─────────────────────────────────────────────────────

    @Test
    void getBucketPolicyReturnsThePolicyJson() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getBucketPolicy(any(GetBucketPolicyRequest.class)))
                .thenReturn(GetBucketPolicyResponse.builder().policy("{\"Version\":\"2012-10-17\"}").build());

        assertEquals("{\"Version\":\"2012-10-17\"}", s3Service.getBucketPolicy("b"));
    }

    @Test
    void getBucketPolicyReturnsNullWhenNoneConfigured() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getBucketPolicy(any(GetBucketPolicyRequest.class))).thenThrow(s3Error("NoSuchBucketPolicy"));

        assertNull(s3Service.getBucketPolicy("b"));
    }

    @Test
    void getBucketPolicyPropagatesOtherS3Errors() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getBucketPolicy(any(GetBucketPolicyRequest.class))).thenThrow(s3Error("AccessDenied"));

        assertThrows(S3Exception.class, () -> s3Service.getBucketPolicy("b"));
    }

    @Test
    void putBucketPolicyForwardsTheJsonVerbatim() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));

        s3Service.putBucketPolicy("b", "{\"Statement\":[]}");

        ArgumentCaptor<PutBucketPolicyRequest> captor = ArgumentCaptor.forClass(PutBucketPolicyRequest.class);
        verify(s3Client).putBucketPolicy(captor.capture());
        assertEquals("b", captor.getValue().bucket());
        assertEquals("{\"Statement\":[]}", captor.getValue().policy());
    }

    // ── Bucket CORS ──────────────────────────────────────────────────────

    @Test
    void getBucketCorsMapsRulesToDtos() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getBucketCors(any(GetBucketCorsRequest.class))).thenReturn(GetBucketCorsResponse.builder()
                .corsRules(CORSRule.builder()
                        .id("rule-1")
                        .allowedMethods("GET", "PUT")
                        .allowedOrigins("https://example.com")
                        .allowedHeaders("*")
                        .maxAgeSeconds(600)
                        .build())
                .build());

        List<CorsRuleDto> rules = s3Service.getBucketCors("b");

        assertEquals(1, rules.size());
        assertEquals("rule-1", rules.get(0).id());
        assertEquals(List.of("GET", "PUT"), rules.get(0).allowedMethods());
        assertEquals(List.of("https://example.com"), rules.get(0).allowedOrigins());
        assertEquals(600, rules.get(0).maxAgeSeconds());
    }

    @Test
    void getBucketCorsReturnsNullWhenNoneConfigured() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getBucketCors(any(GetBucketCorsRequest.class))).thenThrow(s3Error("NoSuchCORSConfiguration"));

        assertNull(s3Service.getBucketCors("b"));
    }

    @Test
    void putBucketCorsBuildsCorsConfigurationFromDtos() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));

        s3Service.putBucketCors("b", List.of(new CorsRuleDto(
                "r", List.of("GET"), List.of("*"), null, List.of("ETag"), 3000)));

        ArgumentCaptor<PutBucketCorsRequest> captor = ArgumentCaptor.forClass(PutBucketCorsRequest.class);
        verify(s3Client).putBucketCors(captor.capture());
        CORSRule sent = captor.getValue().corsConfiguration().corsRules().get(0);
        assertEquals("r", sent.id());
        assertEquals(List.of("GET"), sent.allowedMethods());
        assertEquals(List.of("*"), sent.allowedOrigins());
        assertEquals(List.of(), sent.allowedHeaders());
        assertEquals(List.of("ETag"), sent.exposeHeaders());
        assertEquals(3000, sent.maxAgeSeconds());
    }

    // ── Bucket versioning ────────────────────────────────────────────────

    private static LifecycleRule otherRule() {
        return LifecycleRule.builder().id("user-rule").status(ExpirationStatus.ENABLED).build();
    }

    @Test
    void getBucketVersioningReportsStatusAndRetention() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(GetBucketVersioningResponse.builder().status(BucketVersioningStatus.ENABLED).build());
        when(s3Client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenReturn(GetBucketLifecycleConfigurationResponse.builder().rules(otherRule(),
                        LifecycleRule.builder().id(S3Service.RETENTION_RULE_ID)
                                .noncurrentVersionExpiration(NoncurrentVersionExpiration.builder()
                                        .newerNoncurrentVersions(5).noncurrentDays(7).build())
                                .build()).build());

        S3Service.VersioningConfig config = s3Service.getBucketVersioning("b");

        assertEquals("Enabled", config.status());
        assertEquals(5, config.maxVersions());
        assertEquals(7, config.noncurrentDays());
    }

    @Test
    void getBucketVersioningWithoutStatusOrLifecycleIsOff() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(GetBucketVersioningResponse.builder().build());
        when(s3Client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenThrow(s3Error("NoSuchLifecycleConfiguration"));

        S3Service.VersioningConfig config = s3Service.getBucketVersioning("b");

        assertEquals("Off", config.status());
        assertNull(config.maxVersions());
    }

    @Test
    void putBucketVersioningPreservesOtherLifecycleRules() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenReturn(GetBucketLifecycleConfigurationResponse.builder().rules(otherRule()).build());

        s3Service.putBucketVersioning("b", true, 3, 2);

        ArgumentCaptor<PutBucketVersioningRequest> versioning = ArgumentCaptor.forClass(PutBucketVersioningRequest.class);
        verify(s3Client).putBucketVersioning(versioning.capture());
        assertEquals(BucketVersioningStatus.ENABLED, versioning.getValue().versioningConfiguration().status());
        ArgumentCaptor<PutBucketLifecycleConfigurationRequest> lifecycle =
                ArgumentCaptor.forClass(PutBucketLifecycleConfigurationRequest.class);
        verify(s3Client).putBucketLifecycleConfiguration(lifecycle.capture());
        List<LifecycleRule> sent = lifecycle.getValue().lifecycleConfiguration().rules();
        assertEquals(2, sent.size());
        assertEquals("user-rule", sent.get(0).id());
        assertEquals(S3Service.RETENTION_RULE_ID, sent.get(1).id());
        assertEquals(3, sent.get(1).noncurrentVersionExpiration().newerNoncurrentVersions());
        assertEquals(2, sent.get(1).noncurrentVersionExpiration().noncurrentDays());
    }

    @Test
    void putBucketVersioningWithoutRetentionRemovesOnlyOwnRule() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenReturn(GetBucketLifecycleConfigurationResponse.builder().rules(otherRule(),
                        LifecycleRule.builder().id(S3Service.RETENTION_RULE_ID).build()).build());

        s3Service.putBucketVersioning("b", true, null, null);

        ArgumentCaptor<PutBucketLifecycleConfigurationRequest> lifecycle =
                ArgumentCaptor.forClass(PutBucketLifecycleConfigurationRequest.class);
        verify(s3Client).putBucketLifecycleConfiguration(lifecycle.capture());
        List<LifecycleRule> sent = lifecycle.getValue().lifecycleConfiguration().rules();
        assertEquals(1, sent.size());
        assertEquals("user-rule", sent.get(0).id());
    }

    @Test
    void putBucketVersioningDeletesLifecycleWhenOnlyOwnRuleRemoved() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenReturn(GetBucketLifecycleConfigurationResponse.builder()
                        .rules(LifecycleRule.builder().id(S3Service.RETENTION_RULE_ID).build()).build());

        s3Service.putBucketVersioning("b", false, 3, 1);

        verify(s3Client).deleteBucketLifecycle(any(DeleteBucketLifecycleRequest.class));
        verify(s3Client, org.mockito.Mockito.never())
                .putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
    }

    @Test
    void putBucketVersioningLeavesLifecycleAloneWhenNothingToChange() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenThrow(s3Error("NoSuchLifecycleConfiguration"));

        s3Service.putBucketVersioning("b", false, null, null);

        verify(s3Client, org.mockito.Mockito.never())
                .putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
        verify(s3Client, org.mockito.Mockito.never()).deleteBucketLifecycle(any(DeleteBucketLifecycleRequest.class));
    }

    // ── ZIP download ─────────────────────────────────────────────────────

    @Test
    void planZipKeepsSelectedNamesAndSanitizesEntries() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));

        var plan = s3Service.planZip("b", List.of("docs/readme.txt", "x/../evil.txt"));

        assertEquals(List.of("readme.txt", "evil.txt"), List.copyOf(plan.keySet()));
        assertEquals("docs/readme.txt", plan.get("readme.txt"));
    }

    @Test
    void sanitizeZipEntryRemovesTraversal() {
        assertEquals("a/b.txt", S3Service.sanitizeZipEntry("/../a/./b.txt"));
        assertEquals("", S3Service.sanitizeZipEntry("../"));
    }

    @Test
    void writeZipStreamsObjectsAndDirectoryEntries() throws Exception {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                .thenReturn(new software.amazon.awssdk.core.ResponseInputStream<>(
                        software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),
                        software.amazon.awssdk.http.AbortableInputStream.create(
                                new java.io.ByteArrayInputStream("hello".getBytes()))));
        var plan = new java.util.LinkedHashMap<String, String>();
        plan.put("dir/", "dir/");
        plan.put("dir/a.txt", "dir/a.txt");
        var out = new java.io.ByteArrayOutputStream();

        s3Service.writeZip("b", plan, out);

        try (var zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            assertEquals("dir/", zin.getNextEntry().getName());
            var file = zin.getNextEntry();
            assertEquals("dir/a.txt", file.getName());
            assertEquals("hello", new String(zin.readAllBytes()));
        }
    }

    // ── Object versions ──────────────────────────────────────────────────

    @Test
    void listObjectVersionsReturnsOnlyExactKeyNewestFirstIncludingDeleteMarkers() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.listObjectVersionsPaginator(any(ListObjectVersionsRequest.class)))
                .thenReturn(new software.amazon.awssdk.services.s3.paginators.ListObjectVersionsIterable(
                        s3Client, ListObjectVersionsRequest.builder().bucket("b").prefix("a.txt").build()));
        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder()
                        .versions(
                                ObjectVersion.builder().key("a.txt").versionId("v1").size(5L).isLatest(false)
                                        .lastModified(Instant.parse("2026-01-01T00:00:00Z")).build(),
                                ObjectVersion.builder().key("a.txt.bak").versionId("x").size(1L)
                                        .lastModified(Instant.parse("2026-01-05T00:00:00Z")).build())
                        .deleteMarkers(DeleteMarkerEntry.builder().key("a.txt").versionId("v2").isLatest(true)
                                .lastModified(Instant.parse("2026-02-01T00:00:00Z")).build())
                        .isTruncated(false)
                        .build());

        List<ObjectVersionDto> versions = s3Service.listObjectVersions("b", "a.txt");

        assertEquals(2, versions.size());
        assertEquals("v2", versions.get(0).versionId());
        assertEquals(true, versions.get(0).deleteMarker());
        assertEquals("v1", versions.get(1).versionId());
    }

    @Test
    void restoreObjectVersionCopiesTheVersionOverTheKey() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));

        s3Service.restoreObjectVersion("b", "k", "v1");

        ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(captor.capture());
        assertEquals("v1", captor.getValue().sourceVersionId());
        assertEquals("k", captor.getValue().sourceKey());
        assertEquals("k", captor.getValue().destinationKey());
    }

    @Test
    void deleteObjectVersionPassesTheVersionId() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));

        s3Service.deleteObjectVersion("b", "k", "v1");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertEquals("v1", captor.getValue().versionId());
    }

    // ── Bucket lifecycle ─────────────────────────────────────────────────

    @Test
    void getBucketLifecycleFlagsUnsupportedAndManagedRulesAsReadOnly() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenReturn(GetBucketLifecycleConfigurationResponse.builder().rules(
                        LifecycleRule.builder().id("simple").status(ExpirationStatus.ENABLED)
                                .filter(LifecycleRuleFilter.builder().prefix("logs/").build())
                                .expiration(LifecycleExpiration.builder().days(30).build())
                                .transitions(Transition.builder().days(10)
                                        .storageClass(TransitionStorageClass.GLACIER).build())
                                .build(),
                        LifecycleRule.builder().id("dated").status(ExpirationStatus.ENABLED)
                                .expiration(LifecycleExpiration.builder().date(Instant.parse("2030-01-01T00:00:00Z")).build())
                                .build(),
                        LifecycleRule.builder().id(S3Service.RETENTION_RULE_ID).status(ExpirationStatus.ENABLED).build())
                        .build());

        List<LifecycleRuleDto> rules = s3Service.getBucketLifecycle("b");

        assertEquals(true, rules.get(0).editable());
        assertEquals("logs/", rules.get(0).prefix());
        assertEquals(30, rules.get(0).expirationDays());
        assertEquals("GLACIER", rules.get(0).transitions().get(0).storageClass());
        assertEquals(false, rules.get(1).editable());
        assertEquals(false, rules.get(2).editable());
    }

    @Test
    void putBucketLifecycleKeepsReadOnlyRulesAndReplacesEditableOnes() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        LifecycleRule retention = LifecycleRule.builder().id(S3Service.RETENTION_RULE_ID)
                .status(ExpirationStatus.ENABLED).build();
        when(s3Client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenReturn(GetBucketLifecycleConfigurationResponse.builder().rules(retention,
                        LifecycleRule.builder().id("old").status(ExpirationStatus.ENABLED)
                                .expiration(LifecycleExpiration.builder().days(1).build()).build()).build());

        s3Service.putBucketLifecycle("b", List.of(new LifecycleRuleDto("new", true, "tmp/", 7, null,
                List.of(), null, null, 2, true)));

        ArgumentCaptor<PutBucketLifecycleConfigurationRequest> captor =
                ArgumentCaptor.forClass(PutBucketLifecycleConfigurationRequest.class);
        verify(s3Client).putBucketLifecycleConfiguration(captor.capture());
        List<LifecycleRule> sent = captor.getValue().lifecycleConfiguration().rules();
        assertEquals(2, sent.size());
        assertEquals(S3Service.RETENTION_RULE_ID, sent.get(0).id());
        assertEquals("new", sent.get(1).id());
        assertEquals("tmp/", sent.get(1).filter().prefix());
        assertEquals(7, sent.get(1).expiration().days());
        assertEquals(2, sent.get(1).abortIncompleteMultipartUpload().daysAfterInitiation());
    }

    @Test
    void putBucketLifecycleWithNothingLeftDeletesTheConfiguration() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenThrow(s3Error("NoSuchLifecycleConfiguration"));

        s3Service.putBucketLifecycle("b", List.of());

        verify(s3Client).deleteBucketLifecycle(any(DeleteBucketLifecycleRequest.class));
    }

    @Test
    void ruleWithoutAnyActionIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                S3Service.fromDto(new LifecycleRuleDto("r", true, "", null, null, List.of(), null, null, null, true)));
    }

    private static S3Exception s3Error(String errorCode) {
        return (S3Exception) S3Exception.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build())
                .build();
    }
}