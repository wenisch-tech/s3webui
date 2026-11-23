package tech.wenisch.s3webui.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import tech.wenisch.s3webui.config.MigrationProperties;
import tech.wenisch.s3webui.config.S3ClientFactory;
import tech.wenisch.s3webui.entity.MigrationJob;
import tech.wenisch.s3webui.model.ConflictPolicy;
import tech.wenisch.s3webui.model.MigrationJobView;
import tech.wenisch.s3webui.model.MigrationStatus;
import tech.wenisch.s3webui.model.ResolvedCredential;
import tech.wenisch.s3webui.repository.MigrationJobRepository;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService.EffectiveS3Settings;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Copies buckets or selected objects from one S3 key to another on the server, so nothing has to be
 * downloaded to the browser.
 *
 * <p>A job outlives the HTTP request, the browser tab and even a server restart: it is persisted, only
 * key ids are stored, and unfinished jobs are queued again at startup. Objects that already arrived
 * are recognised and skipped on the second run, so a resumed job carries on where it stopped.
 *
 * <p>Source and destination are separate S3 endpoints, so a server-side {@code CopyObject} is not an
 * option. Objects are streamed from one client into the other, as a single request up to a threshold
 * and as a multipart upload above it.
 */
@Slf4j
@Service
public class MigrationService {

    static final int MAX_ERRORS = 50;
    private static final int MAX_LISTED_JOBS = 50;
    private static final int MIN_PART_SIZE = 5 * 1024 * 1024;
    private static final int MAX_PARTS = 9_000;
    private static final long FLUSH_INTERVAL_MILLIS = 2_000;
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final MigrationJobRepository repository;
    private final S3CredentialAccessService credentialAccessService;
    private final AuditHistoryService auditHistoryService;
    private final JsonMapper jsonMapper;
    private final MigrationProperties properties;
    private final Clock clock;
    private final Function<EffectiveS3Settings, S3Client> clientFactory;
    private final ExecutorService jobExecutor;
    private final Map<String, RunningJob> live = new ConcurrentHashMap<>();

    @Autowired
    public MigrationService(MigrationJobRepository repository,
                            S3CredentialAccessService credentialAccessService,
                            S3ClientFactory s3ClientFactory,
                            AuditHistoryService auditHistoryService,
                            JsonMapper jsonMapper,
                            MigrationProperties properties,
                            Clock clock) {
        this(repository, credentialAccessService, s3ClientFactory::create, auditHistoryService, jsonMapper,
                properties, clock);
    }

    MigrationService(MigrationJobRepository repository,
                     S3CredentialAccessService credentialAccessService,
                     Function<EffectiveS3Settings, S3Client> clientFactory,
                     AuditHistoryService auditHistoryService,
                     JsonMapper jsonMapper,
                     MigrationProperties properties,
                     Clock clock) {
        this.repository = repository;
        this.credentialAccessService = credentialAccessService;
        this.clientFactory = clientFactory;
        this.auditHistoryService = auditHistoryService;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.clock = clock;
        this.jobExecutor = Executors.newFixedThreadPool(
                Math.max(1, properties.getMaxConcurrentJobs()), namedDaemonThreads("migration-job"));
    }

    // ── Submitting and managing jobs ───────────────────────────────────────

    /** What the user asked for. {@code keys} empty means everything below {@code sourcePrefix}. */
    public record SubmitCommand(
            String sourceCredentialId,
            String sourceBucket,
            String sourcePrefix,
            List<String> keys,
            String targetCredentialId,
            String targetBucket,
            String targetPrefix,
            ConflictPolicy conflictPolicy,
            boolean deleteSource
    ) {
    }

    public MigrationJobView submit(SubmitCommand command, Authentication authentication) {
        String sourceBucket = requireText(command.sourceBucket(), "Source bucket is required");
        String targetBucket = requireText(command.targetBucket(), "Target bucket is required");
        String sourceCredentialId = requireText(command.sourceCredentialId(),
                "Migration needs a stored S3 key as the source. Pick a stored key instead of your own credentials.");
        String targetCredentialId = requireText(command.targetCredentialId(), "Pick the key to migrate to");

        // Access is decided now, with the submitting user. A resumed job no longer has one.
        ResolvedCredential source = credentialAccessService.resolve(authentication, sourceCredentialId);
        ResolvedCredential target = credentialAccessService.resolve(authentication, targetCredentialId);

        String sourcePrefix = folderPrefix(command.sourcePrefix(), false);
        String targetPrefix = folderPrefix(command.targetPrefix(), true);
        List<String> keys = command.keys() == null ? List.of()
                : command.keys().stream().filter(key -> key != null && !key.isBlank()).distinct().toList();

        rejectTargetInsideSource(source, target, sourceBucket, targetBucket, sourcePrefix, targetPrefix, keys);

        Instant now = clock.instant();
        MigrationJob job = MigrationJob.builder()
                .id(UUID.randomUUID().toString())
                .ownerName(ownerOf(authentication))
                .status(MigrationStatus.QUEUED)
                .sourceCredentialId(source.id())
                .sourceCredentialName(source.name())
                .sourceBucket(sourceBucket)
                .sourcePrefix(sourcePrefix)
                .selectedKeys(toJson(keys))
                .targetCredentialId(target.id())
                .targetCredentialName(target.name())
                .targetBucket(targetBucket)
                .targetPrefix(targetPrefix)
                .conflictPolicy(command.conflictPolicy() == null ? ConflictPolicy.SKIP : command.conflictPolicy())
                .deleteSource(command.deleteSource())
                .errorLog("[]")
                .createdAt(now)
                .updatedAt(now)
                .build();
        repository.save(job);
        purgeOldJobs();

        RunningJob running = new RunningJob(job);
        live.put(job.getId(), running);
        auditHistoryService.recordFor(job.getOwnerName(), job.getSourceCredentialName(), "MIGRATE",
                "BUCKET", sourceBucket, null,
                "Started migration to " + target.name() + " / " + targetBucket
                        + (keys.isEmpty() ? " (everything below '" + sourcePrefix + "')" : " (" + keys.size() + " selected)"));
        jobExecutor.execute(() -> run(running));
        return running.snapshotView();
    }

    /** Jobs the user may see, newest first: their own, or everyone's for an administrator. */
    public List<MigrationJobView> list(Authentication authentication) {
        PageRequest page = PageRequest.of(0, MAX_LISTED_JOBS);
        List<MigrationJob> jobs = isAdmin(authentication)
                ? repository.findAllByOrderByCreatedAtDesc(page)
                : repository.findByOwnerNameOrderByCreatedAtDesc(ownerOf(authentication), page);
        return jobs.stream().map(this::view).toList();
    }

    public MigrationJobView get(String id, Authentication authentication) {
        return view(requireVisible(id, authentication));
    }

    public MigrationJobView cancel(String id, Authentication authentication) {
        MigrationJob stored = requireVisible(id, authentication);
        RunningJob running = live.get(id);
        if (running == null) {
            if (stored.getStatus().isActive()) {
                // Not running anywhere (for example left over by a crash): close it out.
                stored.setStatus(MigrationStatus.CANCELLED);
                stored.setFinishedAt(clock.instant());
                repository.save(stored);
            }
            return view(stored);
        }
        running.requestCancel();
        return running.snapshotView();
    }

    public void dismiss(String id, Authentication authentication) {
        MigrationJob stored = requireVisible(id, authentication);
        if (stored.getStatus().isActive() || live.containsKey(id)) {
            throw new IllegalStateException("The migration is still running. Cancel it first.");
        }
        repository.deleteById(id);
    }

    // ── Browsing the destination key ───────────────────────────────────────

    public List<String> listTargetBuckets(Authentication authentication, String credentialId) {
        ResolvedCredential credential = credentialAccessService.resolve(authentication, credentialId);
        try (S3Client client = clientFactory.apply(settings(credential))) {
            return client.listBuckets().buckets().stream()
                    .map(bucket -> bucket.name())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
    }

    public List<String> listTargetFolders(Authentication authentication, String credentialId,
                                          String bucket, String prefix) {
        ResolvedCredential credential = credentialAccessService.resolve(authentication, credentialId);
        String normalizedPrefix = folderPrefix(prefix, true);
        try (S3Client client = clientFactory.apply(settings(credential))) {
            List<String> folders = new ArrayList<>();
            String token = null;
            do {
                ListObjectsV2Response response = client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(requireText(bucket, "Bucket is required"))
                        .prefix(normalizedPrefix)
                        .delimiter("/")
                        .continuationToken(token)
                        .build());
                response.commonPrefixes().stream().map(CommonPrefix::prefix).forEach(folders::add);
                token = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
            } while (token != null);
            return folders;
        }
    }

    // ── Startup recovery ───────────────────────────────────────────────────

    /** Picks up jobs that were still unfinished when the server stopped. */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        purgeOldJobs();
        for (MigrationJob job : repository.findByStatusIn(MigrationStatus.activeStatuses())) {
            if (live.containsKey(job.getId())) {
                continue;
            }
            if (job.getStatus() == MigrationStatus.CANCELLING) {
                job.setStatus(MigrationStatus.CANCELLED);
                job.setFinishedAt(clock.instant());
                repository.save(job);
                continue;
            }
            log.info("Resuming migration {} ({} -> {})", job.getId(), job.getSourceBucket(), job.getTargetBucket());
            job.setStatus(MigrationStatus.QUEUED);
            job.setTotalObjects(0);
            job.setTotalBytes(0);
            job.setCopiedObjects(0);
            job.setCopiedBytes(0);
            job.setSkippedObjects(0);
            job.setFailedObjects(0);
            job.setCurrentKey(null);
            job.setErrorLog("[]");
            job.setUpdatedAt(clock.instant());
            repository.save(job);

            RunningJob running = new RunningJob(job);
            live.put(job.getId(), running);
            jobExecutor.execute(() -> run(running));
        }
    }

    @PreDestroy
    void shutdown() {
        jobExecutor.shutdownNow();
    }

    // ── Running a job ──────────────────────────────────────────────────────

    /** Runs one job to completion on the calling thread. Package-private so tests can drive it directly. */
    void run(RunningJob running) {
        MigrationJob job = running.job;
        try {
            if (running.cancelRequested) {
                running.finish(MigrationStatus.CANCELLED, null);
                return;
            }
            ResolvedCredential source = credentialAccessService.resolveForBackground(job.getSourceCredentialId());
            ResolvedCredential target = credentialAccessService.resolveForBackground(job.getTargetCredentialId());
            running.sameStore = source.endpointUrl().equalsIgnoreCase(target.endpointUrl())
                    && job.getSourceBucket().equals(job.getTargetBucket());

            try (S3Client sourceClient = clientFactory.apply(settings(source));
                 S3Client targetClient = clientFactory.apply(settings(target))) {
                running.markStarted();
                running.setStatus(MigrationStatus.SCANNING);
                scan(running, sourceClient);
                if (running.cancelRequested) {
                    running.finish(MigrationStatus.CANCELLED, null);
                    return;
                }

                ensureBucket(targetClient, job.getTargetBucket(), target);
                running.setStatus(MigrationStatus.RUNNING);
                copyAll(running, sourceClient, targetClient);
            }

            if (running.cancelRequested) {
                running.finish(MigrationStatus.CANCELLED, null);
            } else if (running.failed.get() > 0) {
                running.finish(MigrationStatus.COMPLETED_WITH_ERRORS, null);
            } else {
                running.finish(MigrationStatus.COMPLETED, null);
            }
        } catch (Exception e) {
            log.warn("Migration {} failed: {}", job.getId(), e.toString());
            running.finish(MigrationStatus.FAILED, describe(e));
        } finally {
            live.remove(job.getId());
            auditFinished(running);
        }
    }

    private void scan(RunningJob running, S3Client source) {
        AtomicLong objects = new AtomicLong();
        AtomicLong bytes = new AtomicLong();
        enumerate(running, source, found -> {
            running.totalObjects.set(objects.incrementAndGet());
            running.totalBytes.set(bytes.addAndGet(found.size()));
            running.flush(false);
            return !running.cancelRequested;
        });
    }

    private void copyAll(RunningJob running, S3Client source, S3Client target) throws InterruptedException {
        int threads = Math.max(1, properties.getThreadsPerJob());
        Semaphore permits = new Semaphore(threads * 2);
        ExecutorService workers = Executors.newFixedThreadPool(threads, namedDaemonThreads("migration-copy"));
        try {
            enumerate(running, source, found -> {
                if (running.cancelRequested) {
                    return false;
                }
                permits.acquireUninterruptibly();
                workers.execute(() -> {
                    try {
                        copyOne(running, source, target, found);
                    } finally {
                        permits.release();
                    }
                });
                return true;
            });
        } finally {
            workers.shutdown();
            while (!workers.awaitTermination(1, TimeUnit.SECONDS)) {
                running.flush(false);
            }
        }
    }

    private void copyOne(RunningJob running, S3Client source, S3Client target, SourceObject found) {
        MigrationJob job = running.job;
        if (running.cancelRequested) {
            return; // queued before the user cancelled
        }
        long[] counted = new long[1];
        try {
            String relative = found.key().startsWith(job.getSourcePrefix())
                    ? found.key().substring(job.getSourcePrefix().length())
                    : found.key();
            String targetKey = job.getTargetPrefix() + relative;
            running.currentKey = found.key();

            if (targetKey.isEmpty() || (running.sameStore && found.key().equals(targetKey))) {
                running.skipped.incrementAndGet();
                return;
            }

            HeadObjectResponse existing = headOrNull(target, job.getTargetBucket(), targetKey);
            boolean identical = false;
            boolean skip = false;
            if (existing != null) {
                boolean sameSize = existing.contentLength() != null && existing.contentLength() == found.size();
                // S3 servers report whole seconds, so compare against the job's start rounded down too.
                boolean copiedByThisJob = sameSize && existing.lastModified() != null
                        && !existing.lastModified().isBefore(job.getCreatedAt().truncatedTo(ChronoUnit.SECONDS));
                identical = sameSize;
                if (copiedByThisJob) {
                    skip = true;
                } else if (job.getConflictPolicy() == ConflictPolicy.SKIP) {
                    skip = true;
                } else if (job.getConflictPolicy() == ConflictPolicy.NEWER_ONLY) {
                    skip = found.lastModified() == null || existing.lastModified() == null
                            || !found.lastModified().isAfter(existing.lastModified());
                }
            }

            if (skip) {
                running.skipped.incrementAndGet();
                if (identical && job.isDeleteSource()) {
                    deleteSource(running, source, found.key());
                }
                return;
            }

            transfer(running, source, target, found, targetKey, counted);
            running.copied.incrementAndGet();
            if (job.isDeleteSource()) {
                deleteSource(running, source, found.key());
            }
        } catch (CancelledException e) {
            running.copiedBytes.addAndGet(-counted[0]);
        } catch (Exception e) {
            running.copiedBytes.addAndGet(-counted[0]);
            running.failed.incrementAndGet();
            running.addError(found.key() + ": " + describe(e));
            log.debug("Migration {} could not copy {}", job.getId(), found.key(), e);
        } finally {
            running.flush(false);
        }
    }

    /** Streams one object across. {@code counted[0]} tracks the bytes already added to the progress counter. */
    private void transfer(RunningJob running, S3Client source, S3Client target, SourceObject found,
                          String targetKey, long[] counted) throws IOException {
        MigrationJob job = running.job;
        long threshold = Math.max(1, properties.getMultipartThresholdMb()) * 1024L * 1024L;
        try (ResponseInputStream<GetObjectResponse> in = source.getObject(GetObjectRequest.builder()
                .bucket(job.getSourceBucket()).key(found.key()).build())) {
            GetObjectResponse meta = in.response();
            long length = meta.contentLength() != null ? meta.contentLength() : found.size();

            if (length <= threshold) {
                target.putObject(PutObjectRequest.builder()
                                .bucket(job.getTargetBucket())
                                .key(targetKey)
                                .contentType(meta.contentType())
                                .cacheControl(meta.cacheControl())
                                .contentEncoding(meta.contentEncoding())
                                .contentDisposition(meta.contentDisposition())
                                .contentLanguage(meta.contentLanguage())
                                .metadata(meta.metadata())
                                .build(),
                        RequestBody.fromInputStream(in, length));
                running.copiedBytes.addAndGet(length);
                counted[0] = length;
                return;
            }

            String uploadId = target.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(job.getTargetBucket())
                    .key(targetKey)
                    .contentType(meta.contentType())
                    .cacheControl(meta.cacheControl())
                    .contentEncoding(meta.contentEncoding())
                    .contentDisposition(meta.contentDisposition())
                    .contentLanguage(meta.contentLanguage())
                    .metadata(meta.metadata())
                    .build()).uploadId();
            try {
                uploadParts(running, in, target, targetKey, uploadId, length, counted);
            } catch (Exception e) {
                abortQuietly(target, job.getTargetBucket(), targetKey, uploadId);
                throw e;
            }
        }
    }

    private void uploadParts(RunningJob running, InputStream in, S3Client target, String targetKey,
                             String uploadId, long length, long[] counted) throws IOException {
        MigrationJob job = running.job;
        int partSize = (int) Math.min(Integer.MAX_VALUE,
                Math.max((long) Math.max(5, properties.getPartSizeMb()) * 1024 * 1024,
                        Math.max(MIN_PART_SIZE, (length + MAX_PARTS - 1) / MAX_PARTS)));
        List<CompletedPart> parts = new ArrayList<>();
        int number = 1;
        while (true) {
            if (running.cancelRequested) {
                throw new CancelledException();
            }
            byte[] chunk = in.readNBytes(partSize);
            if (chunk.length == 0) {
                break;
            }
            UploadPartResponse response = target.uploadPart(UploadPartRequest.builder()
                    .bucket(job.getTargetBucket()).key(targetKey).uploadId(uploadId).partNumber(number).build(),
                    RequestBody.fromBytes(chunk));
            parts.add(CompletedPart.builder().partNumber(number).eTag(response.eTag()).build());
            counted[0] += chunk.length;
            running.copiedBytes.addAndGet(chunk.length);
            running.flush(false);
            number++;
        }
        target.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(job.getTargetBucket()).key(targetKey).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                .build());
    }

    private void deleteSource(RunningJob running, S3Client source, String key) {
        try {
            source.deleteObject(DeleteObjectRequest.builder()
                    .bucket(running.job.getSourceBucket()).key(key).build());
        } catch (Exception e) {
            running.failed.incrementAndGet();
            running.addError(key + ": copied, but deleting the source failed: " + describe(e));
        }
    }

    private void abortQuietly(S3Client target, String bucket, String key, String uploadId) {
        try {
            target.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId).build());
        } catch (Exception e) {
            log.warn("Could not abort multipart upload {} for {}/{}: {}", uploadId, bucket, key, e.toString());
        }
    }

    private HeadObjectResponse headOrNull(S3Client client, String bucket, String key) {
        try {
            return client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    private void ensureBucket(S3Client target, String bucket, ResolvedCredential credential) {
        try {
            target.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            CreateBucketRequest.Builder request = CreateBucketRequest.builder().bucket(bucket);
            boolean amazon = credential.endpointUrl() != null && credential.endpointUrl().contains("amazonaws.com");
            if (amazon && credential.region() != null && !"us-east-1".equals(credential.region())) {
                request.createBucketConfiguration(CreateBucketConfiguration.builder()
                        .locationConstraint(credential.region()).build());
            }
            target.createBucket(request.build());
        }
    }

    /** Walks the source objects the job covers. The consumer returns false to stop early. */
    private void enumerate(RunningJob running, S3Client source, Predicate<SourceObject> consumer) {
        MigrationJob job = running.job;
        List<String> keys = fromJson(job.getSelectedKeys());
        if (keys.isEmpty()) {
            listPrefix(source, job.getSourceBucket(), job.getSourcePrefix(), consumer);
            return;
        }
        for (String key : keys) {
            if (running.cancelRequested) {
                return;
            }
            boolean keepGoing;
            if (key.endsWith("/")) {
                keepGoing = listPrefix(source, job.getSourceBucket(), key, consumer);
            } else {
                HeadObjectResponse head = headOrNull(source, job.getSourceBucket(), key);
                if (head == null) {
                    running.failed.incrementAndGet();
                    running.addError(key + ": no longer exists");
                    continue;
                }
                keepGoing = consumer.test(new SourceObject(key,
                        head.contentLength() == null ? 0 : head.contentLength(), head.lastModified()));
            }
            if (!keepGoing) {
                return;
            }
        }
    }

    private boolean listPrefix(S3Client source, String bucket, String prefix, Predicate<SourceObject> consumer) {
        String token = null;
        do {
            ListObjectsV2Response response = source.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix == null || prefix.isEmpty() ? null : prefix)
                    .continuationToken(token)
                    .build());
            for (S3Object object : response.contents()) {
                long size = object.size() == null ? 0 : object.size();
                if (!consumer.test(new SourceObject(object.key(), size, object.lastModified()))) {
                    return false;
                }
            }
            token = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (token != null);
        return true;
    }

    private void auditFinished(RunningJob running) {
        try {
            MigrationJob job = running.job;
            MigrationJobView view = running.snapshotView();
            auditHistoryService.recordFor(job.getOwnerName(), job.getSourceCredentialName(), "MIGRATE",
                    "BUCKET", job.getSourceBucket(), null,
                    view.status() + ": " + view.copiedObjects() + " copied, " + view.skippedObjects()
                            + " skipped, " + view.failedObjects() + " failed");
        } catch (Exception e) {
            log.warn("Could not record the migration audit event: {}", e.toString());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private MigrationJobView view(MigrationJob stored) {
        RunningJob running = live.get(stored.getId());
        return running != null ? running.snapshotView() : toView(stored);
    }

    private MigrationJob requireVisible(String id, Authentication authentication) {
        MigrationJob job = repository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
        if (!isAdmin(authentication) && !job.getOwnerName().equalsIgnoreCase(ownerOf(authentication))) {
            throw new JobNotFoundException(id);
        }
        return job;
    }

    private void purgeOldJobs() {
        int days = properties.getRetentionDays();
        if (days > 0) {
            repository.deleteByFinishedAtBefore(clock.instant().minus(java.time.Duration.ofDays(days)));
        }
    }

    private void rejectTargetInsideSource(ResolvedCredential source, ResolvedCredential target,
                                          String sourceBucket, String targetBucket,
                                          String sourcePrefix, String targetPrefix, List<String> keys) {
        boolean sameStore = source.endpointUrl().equalsIgnoreCase(target.endpointUrl())
                && sourceBucket.equals(targetBucket);
        if (!sameStore) {
            return;
        }
        Collection<String> roots = keys.isEmpty() ? List.of(sourcePrefix)
                : keys.stream().filter(key -> key.endsWith("/")).toList();
        for (String root : roots) {
            if (targetPrefix.startsWith(root)) {
                throw new IllegalArgumentException(
                        "The target is inside the source folder, which would copy it into itself");
            }
        }
    }

    private static EffectiveS3Settings settings(ResolvedCredential credential) {
        return new EffectiveS3Settings(credential.accessKey(), credential.secretKey(), credential.endpointUrl(),
                credential.region(), credential.insecureSkipTlsVerify());
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /** Normalises a folder prefix: no leading slash when asked, a trailing slash unless empty. */
    static String folderPrefix(String value, boolean stripLeadingSlash) {
        if (value == null) {
            return "";
        }
        String prefix = value.trim();
        while (stripLeadingSlash && prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }
        return prefix;
    }

    static String ownerOf(Authentication authentication) {
        String email = AuthenticationIdentity.emailOf(authentication);
        if (email != null) {
            return email;
        }
        return authentication == null ? "anonymous" : authentication.getName();
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
    }

    private static String describe(Throwable e) {
        if (e instanceof S3Exception s3 && s3.awsErrorDetails() != null
                && s3.awsErrorDetails().errorMessage() != null && !s3.awsErrorDetails().errorMessage().isBlank()) {
            return s3.awsErrorDetails().errorMessage();
        }
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String toJson(List<String> values) {
        return jsonMapper.writeValueAsString(values);
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return List.of(jsonMapper.readValue(json, String[].class));
    }

    private MigrationJobView toView(MigrationJob job) {
        return new MigrationJobView(job.getId(), job.getOwnerName(), job.getStatus(), job.getStatus().isActive(),
                job.getSourceCredentialName(), job.getSourceBucket(), job.getSourcePrefix(),
                fromJson(job.getSelectedKeys()), job.getTargetCredentialName(), job.getTargetBucket(),
                job.getTargetPrefix(), job.getConflictPolicy(), job.isDeleteSource(), job.getTotalObjects(),
                job.getTotalBytes(), job.getCopiedObjects(), job.getCopiedBytes(), job.getSkippedObjects(),
                job.getFailedObjects(), job.getCurrentKey(), job.getLastError(), fromJson(job.getErrorLog()),
                job.getCreatedAt(), job.getStartedAt(), job.getUpdatedAt(), job.getFinishedAt());
    }

    private static java.util.concurrent.ThreadFactory namedDaemonThreads(String prefix) {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record SourceObject(String key, long size, Instant lastModified) {
    }

    /** Raised inside a copy when the user cancelled; not an error. */
    private static final class CancelledException extends RuntimeException {
        CancelledException() {
            super("cancelled", null, false, false);
        }
    }

    /** A job that is queued or running, with live counters the UI reads while it works. */
    final class RunningJob {
        final MigrationJob job;
        final AtomicLong totalObjects = new AtomicLong();
        final AtomicLong totalBytes = new AtomicLong();
        final AtomicLong copied = new AtomicLong();
        final AtomicLong copiedBytes = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        private final List<String> errors = new ArrayList<>();
        volatile String currentKey;
        volatile boolean cancelRequested;
        volatile boolean sameStore;
        private volatile long lastFlushMillis;

        RunningJob(MigrationJob job) {
            this.job = job;
            this.errors.addAll(fromJson(job.getErrorLog()));
        }

        void requestCancel() {
            cancelRequested = true;
            synchronized (this) {
                if (job.getStatus().isActive() && job.getStatus() != MigrationStatus.QUEUED) {
                    job.setStatus(MigrationStatus.CANCELLING);
                }
            }
            flush(true);
        }

        void markStarted() {
            synchronized (this) {
                if (job.getStartedAt() == null) {
                    job.setStartedAt(clock.instant());
                }
            }
        }

        void setStatus(MigrationStatus status) {
            synchronized (this) {
                // A cancel that arrived meanwhile keeps winding the job down.
                job.setStatus(cancelRequested && status.isActive() ? MigrationStatus.CANCELLING : status);
            }
            flush(true);
        }

        void addError(String message) {
            synchronized (this) {
                if (errors.size() < MAX_ERRORS) {
                    errors.add(message);
                }
            }
        }

        void finish(MigrationStatus status, String error) {
            synchronized (this) {
                job.setStatus(status);
                job.setLastError(error);
                job.setCurrentKey(null);
                job.setFinishedAt(clock.instant());
            }
            currentKey = null;
            flush(true);
        }

        /** Writes the counters to the database, at most every couple of seconds unless forced. */
        void flush(boolean force) {
            long now = clock.millis();
            if (!force && now - lastFlushMillis < FLUSH_INTERVAL_MILLIS) {
                return;
            }
            synchronized (this) {
                lastFlushMillis = now;
                syncCounters();
                try {
                    repository.save(job);
                } catch (RuntimeException e) {
                    log.warn("Could not persist progress of migration {}: {}", job.getId(), e.toString());
                }
            }
        }

        MigrationJobView snapshotView() {
            synchronized (this) {
                syncCounters();
                return toView(job);
            }
        }

        private void syncCounters() {
            job.setTotalObjects(totalObjects.get());
            job.setTotalBytes(totalBytes.get());
            job.setCopiedObjects(copied.get());
            job.setCopiedBytes(Math.max(0, copiedBytes.get()));
            job.setSkippedObjects(skipped.get());
            job.setFailedObjects(failed.get());
            job.setCurrentKey(currentKey);
            job.setErrorLog(toJson(errors));
            job.setUpdatedAt(clock.instant());
        }
    }
}
