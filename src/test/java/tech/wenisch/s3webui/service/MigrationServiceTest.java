package tech.wenisch.s3webui.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import tech.wenisch.s3webui.config.MigrationProperties;
import tech.wenisch.s3webui.entity.MigrationJob;
import tech.wenisch.s3webui.model.ConflictPolicy;
import tech.wenisch.s3webui.model.MigrationJobView;
import tech.wenisch.s3webui.model.MigrationStatus;
import tech.wenisch.s3webui.model.ResolvedCredential;
import tech.wenisch.s3webui.repository.MigrationJobRepository;
import tech.wenisch.s3webui.service.MigrationService.SubmitCommand;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MigrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");
    private static final Instant OLD = Instant.parse("2025-01-01T00:00:00Z");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final MigrationJobRepository repository = mock(MigrationJobRepository.class);
    private final S3CredentialAccessService access = mock(S3CredentialAccessService.class);
    private final AuditHistoryService audit = mock(AuditHistoryService.class);
    private final S3Client source = mock(S3Client.class);
    private final S3Client target = mock(S3Client.class);
    private final Map<String, byte[]> sourceObjects = new LinkedHashMap<>();
    private final MigrationProperties properties = new MigrationProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private MigrationService service;

    private final ResolvedCredential sourceCredential =
            new ResolvedCredential("1", "Source key", "ak1", "sk1", "http://source.example", "us-east-1", false);
    private final ResolvedCredential targetCredential =
            new ResolvedCredential("2", "Target key", "ak2", "sk2", "http://target.example", "us-east-1", false);

    @BeforeEach
    void setUp() {
        properties.setThreadsPerJob(1);
        properties.setMaxConcurrentJobs(1);
        service = new MigrationService(repository, access,
                settings -> "ak1".equals(settings.accessKey()) ? source : target,
                audit, jsonMapper, properties, clock);

        when(access.resolveForBackground("1")).thenReturn(sourceCredential);
        when(access.resolveForBackground("2")).thenReturn(targetCredential);
        when(source.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(invocation -> {
            ListObjectsV2Request request = invocation.getArgument(0);
            List<S3Object> contents = sourceObjects.entrySet().stream()
                    .filter(entry -> request.prefix() == null || entry.getKey().startsWith(request.prefix()))
                    .map(entry -> S3Object.builder().key(entry.getKey()).size((long) entry.getValue().length)
                            .lastModified(NOW.minusSeconds(3600)).build())
                    .toList();
            return ListObjectsV2Response.builder().contents(contents).isTruncated(false).build();
        });
        when(source.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
            GetObjectRequest request = invocation.getArgument(0);
            byte[] data = sourceObjects.get(request.key());
            return new ResponseInputStream<>(GetObjectResponse.builder()
                    .contentLength((long) data.length).contentType("text/plain").build(),
                    AbortableInputStream.create(new ByteArrayInputStream(data)));
        });
        when(source.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectRequest request = invocation.getArgument(0);
            byte[] data = sourceObjects.get(request.key());
            if (data == null) {
                throw missing();
            }
            return HeadObjectResponse.builder().contentLength((long) data.length)
                    .lastModified(NOW.minusSeconds(3600)).build();
        });
        when(target.headObject(any(HeadObjectRequest.class))).thenThrow(missing());
    }

    // ── Copying ────────────────────────────────────────────────────────────

    @Test
    void copiesTheWholeBucketUnderTheTargetPrefixAndReportsProgress() throws IOException {
        sourceObjects.put("a.txt", "hello".getBytes());
        sourceObjects.put("dir/b.txt", "world!".getBytes());

        MigrationJobView view = runJob(job("", List.of(), "backup/", ConflictPolicy.SKIP, false));

        assertEquals(MigrationStatus.COMPLETED, view.status());
        assertEquals(2, view.totalObjects());
        assertEquals(2, view.copiedObjects());
        assertEquals(11, view.copiedBytes());
        assertEquals(0, view.failedObjects());

        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodies = ArgumentCaptor.forClass(RequestBody.class);
        verify(target, times(2)).putObject(requests.capture(), bodies.capture());
        assertEquals(List.of("backup/a.txt", "backup/dir/b.txt"),
                requests.getAllValues().stream().map(PutObjectRequest::key).toList());
        assertEquals("text/plain", requests.getAllValues().get(0).contentType());
        assertEquals("hello", new String(bodies.getAllValues().get(0).contentStreamProvider().newStream().readAllBytes()));
        verify(source, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void aSelectedFolderKeepsItsNameBelowTheTargetPrefix() {
        sourceObjects.put("docs/img/x.png", "x".getBytes());
        sourceObjects.put("docs/other.txt", "o".getBytes());

        MigrationJobView view = runJob(job("docs/", List.of("docs/img/"), "t/", ConflictPolicy.SKIP, false));

        assertEquals(1, view.copiedObjects());
        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(target).putObject(requests.capture(), any(RequestBody.class));
        assertEquals("t/img/x.png", requests.getValue().key());
    }

    @Test
    void aSelectedFileLandsDirectlyInTheTargetPrefix() {
        sourceObjects.put("docs/report.pdf", "pdf".getBytes());

        runJob(job("docs/", List.of("docs/report.pdf"), "inbox/", ConflictPolicy.SKIP, false));

        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(target).putObject(requests.capture(), any(RequestBody.class));
        assertEquals("inbox/report.pdf", requests.getValue().key());
    }

    @Test
    void createsTheTargetBucketWhenItDoesNotExist() {
        sourceObjects.put("a.txt", "a".getBytes());
        when(target.headBucket(any(HeadBucketRequest.class))).thenThrow(
                NoSuchBucketException.builder().statusCode(404).build());

        MigrationJobView view = runJob(job("", List.of(), "", ConflictPolicy.SKIP, false));

        assertEquals(MigrationStatus.COMPLETED, view.status());
        ArgumentCaptor<CreateBucketRequest> created = ArgumentCaptor.forClass(CreateBucketRequest.class);
        verify(target).createBucket(created.capture());
        assertEquals("target-bucket", created.getValue().bucket());
    }

    @Test
    void doesNotCreateABucketWhenTheBucketCheckIsDenied() {
        sourceObjects.put("a.txt", "a".getBytes());
        when(target.headBucket(any(HeadBucketRequest.class))).thenThrow(
                S3Exception.builder().statusCode(403).message("Forbidden").build());

        MigrationJobView view = runJob(job("", List.of(), "", ConflictPolicy.SKIP, false));

        assertEquals(MigrationStatus.FAILED, view.status());
        verify(target, never()).createBucket(any(CreateBucketRequest.class));
    }

    // ── Conflicts ──────────────────────────────────────────────────────────

    @Test
    void skipLeavesAnExistingObjectAlone() {
        sourceObjects.put("a.txt", "hello".getBytes());
        existsAtTarget("a.txt", 5, OLD);

        MigrationJobView view = runJob(job("", List.of(), "", ConflictPolicy.SKIP, false));

        assertEquals(1, view.skippedObjects());
        assertEquals(0, view.copiedObjects());
        verify(target, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void overwriteReplacesAnExistingObject() {
        sourceObjects.put("a.txt", "hello".getBytes());
        existsAtTarget("a.txt", 3, OLD);

        MigrationJobView view = runJob(job("", List.of(), "", ConflictPolicy.OVERWRITE, false));

        assertEquals(1, view.copiedObjects());
        verify(target).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void newerOnlyCopiesWhenTheSourceIsNewerAndSkipsOtherwise() {
        sourceObjects.put("new.txt", "n".getBytes());
        sourceObjects.put("old.txt", "o".getBytes());
        // source objects were modified one hour before NOW
        existsAtTarget("new.txt", 1, NOW.minusSeconds(7200).minusSeconds(86_400));
        existsAtTarget("old.txt", 1, OLD.plusSeconds(400_000_000L));

        MigrationJobView view = runJob(job("", List.of(), "", ConflictPolicy.NEWER_ONLY, false));

        assertEquals(1, view.copiedObjects());
        assertEquals(1, view.skippedObjects());
        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(target).putObject(requests.capture(), any(RequestBody.class));
        assertEquals("new.txt", requests.getValue().key());
    }

    @Test
    void anObjectAlreadyWrittenByThisJobIsNotCopiedAgainEvenWhenOverwriting() {
        sourceObjects.put("a.txt", "hello".getBytes());
        existsAtTarget("a.txt", 5, NOW.plusSeconds(5));

        MigrationJobView view = runJob(job("", List.of(), "", ConflictPolicy.OVERWRITE, false));

        assertEquals(1, view.skippedObjects());
        verify(target, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void anObjectWrittenInTheFirstSecondOfTheJobCountsAsCopiedDespiteWholeSecondTimestamps() {
        sourceObjects.put("a.txt", "hello".getBytes());
        MigrationJob job = job("", List.of(), "", ConflictPolicy.OVERWRITE, false);
        job.setCreatedAt(NOW.plusMillis(700));
        existsAtTarget("a.txt", 5, NOW); // the server rounded 12:00:00.9 down to 12:00:00

        MigrationJobView view = runJob(job);

        assertEquals(1, view.skippedObjects());
        verify(target, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void copyingAnObjectOntoItselfIsSkippedAndNeverDeletesTheSource() {
        sourceObjects.put("a.txt", "hello".getBytes());
        MigrationJob job = job("", List.of(), "", ConflictPolicy.OVERWRITE, true);
        job.setTargetCredentialId("1");
        job.setTargetBucket(job.getSourceBucket());
        when(access.resolveForBackground("1")).thenReturn(sourceCredential);

        MigrationJobView view = runJob(job);

        assertEquals(1, view.skippedObjects());
        verify(source, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    // ── Deleting the source ────────────────────────────────────────────────

    @Test
    void deletesTheSourceOnlyAfterACopySucceeded() {
        sourceObjects.put("good.txt", "g".getBytes());
        sourceObjects.put("bad.txt", "b".getBytes());
        when(target.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            PutObjectRequest request = invocation.getArgument(0);
            if (request.key().equals("bad.txt")) {
                throw S3Exception.builder().statusCode(500).message("boom").build();
            }
            return null;
        });

        MigrationJobView view = runJob(job("", List.of(), "", ConflictPolicy.SKIP, true));

        assertEquals(MigrationStatus.COMPLETED_WITH_ERRORS, view.status());
        assertEquals(1, view.copiedObjects());
        assertEquals(1, view.failedObjects());
        assertTrue(view.errors().get(0).startsWith("bad.txt: "), view.errors().toString());
        assertEquals(1, view.copiedBytes(), "bytes of the failed object do not count");
        ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(source).deleteObject(deleted.capture());
        assertEquals("good.txt", deleted.getValue().key());
    }

    @Test
    void anExistingIdenticalObjectLetsTheSourceBeDeleted() {
        sourceObjects.put("a.txt", "hello".getBytes());
        existsAtTarget("a.txt", 5, OLD);

        runJob(job("", List.of(), "", ConflictPolicy.SKIP, true));

        verify(source).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void aDifferentExistingObjectKeepsTheSourceWhenSkipping() {
        sourceObjects.put("a.txt", "hello".getBytes());
        existsAtTarget("a.txt", 99, OLD);

        runJob(job("", List.of(), "", ConflictPolicy.SKIP, true));

        verify(source, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    // ── Large objects ──────────────────────────────────────────────────────

    @Test
    void largeObjectsAreCopiedAsAMultipartUpload() {
        properties.setMultipartThresholdMb(1);
        properties.setPartSizeMb(5);
        sourceObjects.put("big.bin", new byte[6 * 1024 * 1024]);
        when(target.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("up1").build());
        when(target.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("etag").build());

        MigrationJobView view = runJob(job("", List.of(), "", ConflictPolicy.SKIP, false));

        assertEquals(MigrationStatus.COMPLETED, view.status());
        assertEquals(6 * 1024 * 1024, view.copiedBytes());
        verify(target, times(2)).uploadPart(any(UploadPartRequest.class), any(RequestBody.class));
        ArgumentCaptor<CompleteMultipartUploadRequest> complete =
                ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(target).completeMultipartUpload(complete.capture());
        assertEquals(2, complete.getValue().multipartUpload().parts().size());
        verify(target, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void aFailedPartAbortsTheMultipartUploadAndReleasesItsBytes() {
        properties.setMultipartThresholdMb(1);
        properties.setPartSizeMb(5);
        sourceObjects.put("big.bin", new byte[6 * 1024 * 1024]);
        when(target.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("up1").build());
        when(target.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("etag").build())
                .thenThrow(S3Exception.builder().statusCode(500).message("part failed").build());

        MigrationJobView view = runJob(job("", List.of(), "", ConflictPolicy.SKIP, true));

        assertEquals(MigrationStatus.COMPLETED_WITH_ERRORS, view.status());
        assertEquals(0, view.copiedBytes());
        verify(target).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        verify(source, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    // ── Cancelling and failing ─────────────────────────────────────────────

    @Test
    void aJobCancelledBeforeItStartsNeverTouchesS3() {
        sourceObjects.put("a.txt", "a".getBytes());
        MigrationService.RunningJob running = service.new RunningJob(job("", List.of(), "", ConflictPolicy.SKIP, false));
        running.requestCancel();

        service.run(running);

        assertEquals(MigrationStatus.CANCELLED, running.snapshotView().status());
        verify(target, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(access, never()).resolveForBackground(any());
    }

    @Test
    void cancellingWhileCopyingStopsTheJob() {
        sourceObjects.put("a.txt", "a".getBytes());
        sourceObjects.put("b.txt", "b".getBytes());
        MigrationService.RunningJob running = service.new RunningJob(job("", List.of(), "", ConflictPolicy.SKIP, false));
        when(target.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            running.requestCancel();
            return null;
        });

        service.run(running);

        MigrationJobView view = running.snapshotView();
        assertEquals(MigrationStatus.CANCELLED, view.status());
        assertTrue(view.copiedObjects() < 2, "the second object must not be started");
        assertTrue(view.finishedAt() != null);
    }

    @Test
    void aKeyThatCannotBeResolvedFailsTheJobWithItsMessage() {
        when(access.resolveForBackground("2")).thenThrow(new MissingS3ConfigurationException("key is gone"));

        MigrationJobView view = runJob(job("", List.of(), "", ConflictPolicy.SKIP, false));

        assertEquals(MigrationStatus.FAILED, view.status());
        assertEquals("key is gone", view.lastError());
    }

    @Test
    void aSelectedFileThatVanishedIsReportedAsAnError() {
        MigrationJobView view = runJob(job("", List.of("ghost.txt"), "", ConflictPolicy.SKIP, false));

        assertEquals(MigrationStatus.COMPLETED_WITH_ERRORS, view.status());
        assertEquals("ghost.txt: no longer exists", view.errors().get(0));
    }

    @Test
    void finishingAJobWritesAnAuditEntryForTheOwner() {
        sourceObjects.put("a.txt", "a".getBytes());

        runJob(job("", List.of(), "", ConflictPolicy.SKIP, false));

        verify(audit).recordFor(org.mockito.ArgumentMatchers.eq("alice@example.com"),
                org.mockito.ArgumentMatchers.eq("Source key"), org.mockito.ArgumentMatchers.eq("MIGRATE"),
                org.mockito.ArgumentMatchers.eq("BUCKET"), org.mockito.ArgumentMatchers.eq("source-bucket"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.startsWith("COMPLETED"));
    }

    // ── Submitting ─────────────────────────────────────────────────────────

    @Test
    void submitChecksAccessToBothKeysWithTheSubmittingUser() {
        Authentication alice = user("alice@example.com");
        when(access.resolve(alice, "1")).thenReturn(sourceCredential);
        when(access.resolve(alice, "2")).thenReturn(targetCredential);
        sourceObjects.put("a.txt", "a".getBytes());

        MigrationJobView view = service.submit(command("1", "src", "", List.of(), "2", "dst", "x"), alice);

        assertEquals("alice@example.com", view.owner());
        assertEquals("Source key", view.sourceCredentialName());
        assertEquals("Target key", view.targetCredentialName());
        assertEquals("x/", view.targetPrefix());
        verify(access).resolve(alice, "1");
        verify(access).resolve(alice, "2");
        service.shutdown();
    }

    @Test
    void submitRefusesASessionOnlyKeyAsSource() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.submit(command(null, "src", "", List.of(), "2", "dst", ""), user("alice@example.com")));

        assertTrue(error.getMessage().contains("stored S3 key"));
    }

    @Test
    void submitRefusesATargetInsideTheSourceFolder() {
        Authentication alice = user("alice@example.com");
        when(access.resolve(alice, "1")).thenReturn(sourceCredential);
        when(access.resolve(alice, "2")).thenReturn(sourceCredential);

        assertThrows(IllegalArgumentException.class, () ->
                service.submit(command("1", "src", "data/", List.of(), "2", "src", "data/copy"), alice));
        assertThrows(IllegalArgumentException.class, () ->
                service.submit(command("1", "src", "", List.of(), "2", "src", "anything"), alice));
        assertThrows(IllegalArgumentException.class, () ->
                service.submit(command("1", "src", "", List.of("a/"), "2", "src", "a/b"), alice));
    }

    @Test
    void submitRequiresBothBuckets() {
        assertThrows(IllegalArgumentException.class, () ->
                service.submit(command("1", " ", "", List.of(), "2", "dst", ""), user("a@b.c")));
        assertThrows(IllegalArgumentException.class, () ->
                service.submit(command("1", "src", "", List.of(), "2", "", ""), user("a@b.c")));
    }

    // ── Visibility ─────────────────────────────────────────────────────────

    @Test
    void usersOnlySeeAndCancelTheirOwnJobsButAdministratorsSeeAll() {
        MigrationJob job = job("", List.of(), "", ConflictPolicy.SKIP, false);
        job.setStatus(MigrationStatus.COMPLETED);
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));

        assertEquals(job.getId(), service.get(job.getId(), user("alice@example.com")).id());
        assertThrows(JobNotFoundException.class, () -> service.get(job.getId(), user("mallory@example.com")));
        assertThrows(JobNotFoundException.class, () -> service.cancel(job.getId(), user("mallory@example.com")));
        assertEquals(job.getId(), service.get(job.getId(), admin()).id());
        assertThrows(JobNotFoundException.class, () -> service.get("unknown", admin()));
    }

    @Test
    void aRunningJobCannotBeDismissedButAFinishedOneCan() {
        MigrationJob running = job("", List.of(), "", ConflictPolicy.SKIP, false);
        running.setStatus(MigrationStatus.RUNNING);
        MigrationJob finished = job("", List.of(), "", ConflictPolicy.SKIP, false);
        finished.setStatus(MigrationStatus.COMPLETED);
        when(repository.findById(running.getId())).thenReturn(Optional.of(running));
        when(repository.findById(finished.getId())).thenReturn(Optional.of(finished));

        assertThrows(IllegalStateException.class, () -> service.dismiss(running.getId(), user("alice@example.com")));
        service.dismiss(finished.getId(), user("alice@example.com"));

        verify(repository).deleteById(finished.getId());
    }

    // ── Recovery ───────────────────────────────────────────────────────────

    @Test
    void recoveryQueuesUnfinishedJobsAgainAndClosesCancellingOnes() {
        MigrationJob interrupted = job("", List.of(), "", ConflictPolicy.SKIP, false);
        interrupted.setStatus(MigrationStatus.RUNNING);
        interrupted.setCopiedObjects(7);
        MigrationJob cancelling = job("", List.of(), "", ConflictPolicy.SKIP, false);
        cancelling.setStatus(MigrationStatus.CANCELLING);
        when(repository.findByStatusIn(MigrationStatus.activeStatuses())).thenReturn(List.of(interrupted, cancelling));
        when(access.resolveForBackground("1")).thenThrow(new MissingS3ConfigurationException("not now"));

        service.recover();

        assertEquals(MigrationStatus.CANCELLED, cancelling.getStatus());
        assertEquals(0, interrupted.getCopiedObjects(), "counters restart because the job is scanned again");
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(interrupted);
        service.shutdown();
    }

    @Test
    void folderPrefixesAreNormalised() {
        assertEquals("", MigrationService.folderPrefix(null, true));
        assertEquals("", MigrationService.folderPrefix("  ", true));
        assertEquals("a/b/", MigrationService.folderPrefix("/a/b", true));
        assertEquals("a/", MigrationService.folderPrefix("a/", false));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private MigrationJobView runJob(MigrationJob job) {
        MigrationService.RunningJob running = service.new RunningJob(job);
        service.run(running);
        return running.snapshotView();
    }

    private MigrationJob job(String sourcePrefix, List<String> keys, String targetPrefix,
                             ConflictPolicy policy, boolean deleteSource) {
        return MigrationJob.builder()
                .id(java.util.UUID.randomUUID().toString())
                .ownerName("alice@example.com")
                .status(MigrationStatus.QUEUED)
                .sourceCredentialId("1")
                .sourceCredentialName("Source key")
                .sourceBucket("source-bucket")
                .sourcePrefix(sourcePrefix)
                .selectedKeys(jsonMapper.writeValueAsString(new ArrayList<>(keys)))
                .targetCredentialId("2")
                .targetCredentialName("Target key")
                .targetBucket("target-bucket")
                .targetPrefix(targetPrefix)
                .conflictPolicy(policy)
                .deleteSource(deleteSource)
                .errorLog("[]")
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private void existsAtTarget(String key, long size, Instant lastModified) {
        org.mockito.Mockito.doReturn(HeadObjectResponse.builder().contentLength(size).lastModified(lastModified).build())
                .when(target).headObject(HeadObjectRequest.builder().bucket("target-bucket").key(key).build());
    }

    private static S3Exception missing() {
        return NoSuchKeyException.builder().statusCode(404).message("missing").build();
    }

    private static SubmitCommand command(String sourceCredential, String sourceBucket, String sourcePrefix,
                                         List<String> keys, String targetCredential, String targetBucket,
                                         String targetPrefix) {
        return new SubmitCommand(sourceCredential, sourceBucket, sourcePrefix, keys, targetCredential,
                targetBucket, targetPrefix, ConflictPolicy.SKIP, false);
    }

    private static Authentication user(String email) {
        return UsernamePasswordAuthenticationToken.authenticated(email, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static Authentication admin() {
        return UsernamePasswordAuthenticationToken.authenticated("root@example.com", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
