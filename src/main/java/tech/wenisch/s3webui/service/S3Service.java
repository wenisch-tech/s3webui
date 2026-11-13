package tech.wenisch.s3webui.service;

import tech.wenisch.s3webui.model.BucketDto;
import tech.wenisch.s3webui.model.CompleteMultipartRequest;
import tech.wenisch.s3webui.model.CorsRuleDto;
import tech.wenisch.s3webui.model.LifecycleRuleDto;
import tech.wenisch.s3webui.model.ObjectVersionDto;
import tech.wenisch.s3webui.model.S3ObjectDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    public List<BucketDto> listBuckets() {
        return s3Client.listBuckets().buckets().stream()
                .map(b -> BucketDto.builder()
                        .name(b.name())
                        .creationDate(b.creationDate())
                        .build())
                .collect(Collectors.toList());
    }

    public void createBucket(String name) {
                boolean bucketAlreadyExists = listBuckets().stream()
                                .anyMatch(bucket -> name.equals(bucket.getName()));
                if (bucketAlreadyExists) {
                        throw new DuplicateBucketException("Bucket '" + name + "' already exists");
                }
        s3Client.createBucket(CreateBucketRequest.builder().bucket(name).build());
    }

    public void deleteBucket(String name) {
        s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(name).build());
    }

    /** Deletes every object (and object version / delete marker) in the bucket, then the bucket itself. */
    public void deleteBucketWithContents(String name) {
        var paginator = s3Client.listObjectVersionsPaginator(
                ListObjectVersionsRequest.builder().bucket(name).build());
        List<ObjectIdentifier> batch = new ArrayList<>();
        for (var page : paginator) {
            for (ObjectVersion v : page.versions()) {
                batch.add(ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build());
            }
            for (DeleteMarkerEntry m : page.deleteMarkers()) {
                batch.add(ObjectIdentifier.builder().key(m.key()).versionId(m.versionId()).build());
            }
            while (batch.size() >= 1000) {
                flushDeleteBatch(name, batch.subList(0, 1000));
            }
        }
        if (!batch.isEmpty()) {
            flushDeleteBatch(name, batch);
        }
        deleteBucket(name);
    }

    private void flushDeleteBatch(String bucket, List<ObjectIdentifier> ids) {
        var response = s3Client.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(new ArrayList<>(ids)).quiet(true).build())
                .build());
        if (response.hasErrors() && !response.errors().isEmpty()) {
            var err = response.errors().get(0);
            throw new IllegalStateException("Failed to delete '" + err.key() + "': " + err.message());
        }
        ids.clear();
    }

    public List<S3ObjectDto> listObjects(String bucket, String prefix) {
        String normalizedPrefix = (prefix == null || prefix.isBlank()) ? "" : prefix;
        if (!normalizedPrefix.isEmpty() && !normalizedPrefix.endsWith("/")) {
            normalizedPrefix = normalizedPrefix + "/";
        }

        var directories = new ArrayList<S3ObjectDto>();
        var files = new ArrayList<S3ObjectDto>();
        Set<String> continuationTokens = new HashSet<>();
        String continuationToken = null;

        do {
            var request = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(normalizedPrefix)
                    .delimiter("/")
                    .continuationToken(continuationToken)
                    .build();
            var response = s3Client.listObjectsV2(request);

            // Add "virtual folders" (common prefixes)
            for (CommonPrefix cp : response.commonPrefixes()) {
                String fullPrefix = cp.prefix();
                String folderName = fullPrefix.substring(normalizedPrefix.length());
                if (folderName.endsWith("/")) {
                    folderName = folderName.substring(0, folderName.length() - 1);
                }
                directories.add(S3ObjectDto.builder()
                        .key(fullPrefix)
                        .name(folderName)
                        .prefix(fullPrefix)
                        .directory(true)
                        .build());
            }

            // Add objects
            for (S3Object obj : response.contents()) {
                String key = obj.key();
                if (key.equals(normalizedPrefix)) {
                    continue; // skip the prefix itself
                }
                String name = key.substring(normalizedPrefix.length());
                files.add(S3ObjectDto.builder()
                        .key(key)
                        .name(name)
                        .size(obj.size())
                        .lastModified(obj.lastModified())
                        .storageClass(obj.storageClassAsString())
                        .directory(false)
                        .build());
            }

            if (!Boolean.TRUE.equals(response.isTruncated())) {
                break;
            }

            continuationToken = response.nextContinuationToken();
            if (continuationToken == null || continuationToken.isBlank()
                    || !continuationTokens.add(continuationToken)) {
                throw new IllegalStateException("S3 returned an invalid continuation token while listing bucket '"
                        + bucket + "' with prefix '" + normalizedPrefix + "'");
            }
        } while (true);

        directories.addAll(files);
        return directories;
    }

    /**
     * Lists every object key in a bucket without applying a delimiter. This is intentionally
     * separate from {@link #listObjects(String, String)}, whose delimiter-based response powers
     * one folder at a time in the browser.
     */
    public List<S3ObjectDto> listAllObjects(String bucket) {
        var objects = new ArrayList<S3ObjectDto>();
        Set<String> continuationTokens = new HashSet<>();
        String continuationToken = null;

        do {
            var request = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .continuationToken(continuationToken)
                    .build();
            var response = s3Client.listObjectsV2(request);

            for (S3Object object : response.contents()) {
                String key = object.key();
                objects.add(S3ObjectDto.builder()
                        .key(key)
                        .name(fileName(key))
                        .size(object.size())
                        .lastModified(object.lastModified())
                        .storageClass(object.storageClassAsString())
                        .directory(key != null && key.endsWith("/"))
                        .build());
            }

            if (!Boolean.TRUE.equals(response.isTruncated())) {
                break;
            }

            continuationToken = response.nextContinuationToken();
            if (continuationToken == null || continuationToken.isBlank()
                    || !continuationTokens.add(continuationToken)) {
                throw new IllegalStateException("S3 returned an invalid continuation token while indexing bucket '"
                        + bucket + "'");
            }
        } while (true);

        return objects;
    }

    private String fileName(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String withoutTrailingSlash = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
        int separator = withoutTrailingSlash.lastIndexOf('/');
        return withoutTrailingSlash.substring(separator + 1);
    }

    public ResponseInputStream<GetObjectResponse> getObject(String bucket, String key) {
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }

    /** Downloads a specific version of an object ({@code versionId} may be null for the latest). */
    public ResponseInputStream<GetObjectResponse> getObject(String bucket, String key, String versionId) {
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .versionId(versionId)
                .build());
    }

    // ── Object versions ───────────────────────────────────────────────────

    /** All versions and delete markers of exactly this key, newest first. */
    public List<ObjectVersionDto> listObjectVersions(String bucket, String key) {
        List<ObjectVersionDto> result = new ArrayList<>();
        var paginator = s3Client.listObjectVersionsPaginator(
                ListObjectVersionsRequest.builder().bucket(bucket).prefix(key).build());
        for (var page : paginator) {
            for (ObjectVersion v : page.versions()) {
                if (key.equals(v.key())) {
                    result.add(new ObjectVersionDto(v.versionId(), Boolean.TRUE.equals(v.isLatest()), false,
                            v.size() == null ? 0 : v.size(), v.lastModified(), v.storageClassAsString()));
                }
            }
            for (DeleteMarkerEntry m : page.deleteMarkers()) {
                if (key.equals(m.key())) {
                    result.add(new ObjectVersionDto(m.versionId(), Boolean.TRUE.equals(m.isLatest()), true,
                            0, m.lastModified(), null));
                }
            }
        }
        result.sort((a, b) -> {
            if (a.lastModified() == null || b.lastModified() == null) {
                return 0;
            }
            return b.lastModified().compareTo(a.lastModified());
        });
        return result;
    }

    /** Makes an old version the latest by copying it over the key (the old version itself is kept). */
    public void restoreObjectVersion(String bucket, String key, String versionId) {
        s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket)
                .sourceKey(key)
                .sourceVersionId(versionId)
                .destinationBucket(bucket)
                .destinationKey(key)
                .build());
    }

    /** Permanently deletes one version (or removes a delete marker, which undeletes the object). */
    public void deleteObjectVersion(String bucket, String key, String versionId) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .versionId(versionId)
                .build());
    }

    public void putObject(String bucket, String key, InputStream inputStream, long contentLength, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .contentLength(contentLength)
                        .build(),
                RequestBody.fromInputStream(inputStream, contentLength));
    }

    public void deleteObject(String bucket, String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }

    /** Expands folder keys (ending in "/") to every object beneath them; file keys pass through. */
    public List<String> expandKeys(String bucket, List<String> keys) {
        Set<String> result = new java.util.LinkedHashSet<>();
        for (String key : keys) {
            if (!key.endsWith("/")) {
                result.add(key);
                continue;
            }
            var paginator = s3Client.listObjectsV2Paginator(
                    ListObjectsV2Request.builder().bucket(bucket).prefix(key).build());
            for (var page : paginator) {
                for (S3Object obj : page.contents()) {
                    result.add(obj.key());
                }
            }
        }
        return new ArrayList<>(result);
    }

    /** Upper bound on objects in one ZIP download. */
    public static final int MAX_ZIP_OBJECTS = 10_000;

    /**
     * Maps ZIP entry name to object key for the selected files/folders. A selected item keeps its own
     * name ("a/b/" becomes "b/..."), so the archive extracts to the same layout the user sees.
     */
    public java.util.Map<String, String> planZip(String bucket, List<String> keys) {
        java.util.Map<String, String> plan = new java.util.LinkedHashMap<>();
        for (String selected : keys) {
            boolean folder = selected.endsWith("/");
            String trimmed = folder ? selected.substring(0, selected.length() - 1) : selected;
            String parent = trimmed.substring(0, trimmed.lastIndexOf('/') + 1);
            for (String key : expandKeys(bucket, List.of(selected))) {
                String entry = sanitizeZipEntry(key.substring(parent.length()));
                if (!entry.isEmpty()) {
                    plan.putIfAbsent(entry, key);
                }
                if (plan.size() > MAX_ZIP_OBJECTS) {
                    throw new IllegalArgumentException(
                            "Too many objects for one ZIP (limit " + MAX_ZIP_OBJECTS + "); select a smaller folder");
                }
            }
        }
        return plan;
    }

    /** Drops leading slashes and "." / ".." segments so an extracted archive cannot escape its folder. */
    static String sanitizeZipEntry(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("/", -1)) {
            if (part.equals("..") || part.equals(".") ) {
                continue;
            }
            if (part.isEmpty() && sb.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /** Streams the planned objects as a ZIP archive. Keys ending in "/" become empty directory entries. */
    public void writeZip(String bucket, java.util.Map<String, String> plan, java.io.OutputStream out)
            throws java.io.IOException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            for (var item : plan.entrySet()) {
                String entryName = item.getKey();
                if (item.getValue().endsWith("/")) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(entryName.endsWith("/") ? entryName : entryName + "/"));
                    zip.closeEntry();
                    continue;
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
                try (ResponseInputStream<GetObjectResponse> in = getObject(bucket, item.getValue())) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }

    /** Deletes the given files and, recursively, the contents of any folder keys. */
    public void deleteKeys(String bucket, List<String> keys) {
        deleteObjects(bucket, expandKeys(bucket, keys));
    }

    /**
     * Copies files (and folder contents) into targetBucket under targetPrefix, then deletes the sources.
     * A selected item keeps its own name: "a/b/" with target "x/" becomes "x/b/...". Returns objects moved.
     */
    public int moveObjects(String sourceBucket, List<String> keys, String targetBucket, String targetPrefix) {
        return transferObjects(sourceBucket, keys, targetBucket, targetPrefix, true);
    }

    /** Copies files (and folder contents) like {@link #moveObjects}, leaving the sources untouched. */
    public int copyObjects(String sourceBucket, List<String> keys, String targetBucket, String targetPrefix) {
        return transferObjects(sourceBucket, keys, targetBucket, targetPrefix, false);
    }

    private int transferObjects(String sourceBucket, List<String> keys, String targetBucket, String targetPrefix,
            boolean deleteSources) {
        String prefix = targetPrefix == null ? "" : targetPrefix;
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }
        if (sourceBucket.equals(targetBucket)) {
            for (String key : keys) {
                String parent = key.substring(0, key.lastIndexOf('/', key.length() - (key.endsWith("/") ? 2 : 1)) + 1);
                if (parent.equals(prefix) || (key.endsWith("/") && prefix.startsWith(key))) {
                    throw new IllegalArgumentException(
                            "Cannot move '" + key + "' into its current location or into itself");
                }
            }
        }
        List<String> moved = new ArrayList<>();
        for (String selected : keys) {
            String trimmed = selected.endsWith("/") ? selected.substring(0, selected.length() - 1) : selected;
            String base = trimmed.substring(trimmed.lastIndexOf('/') + 1);
            String sourceRoot = selected.endsWith("/")
                    ? selected
                    : null;
            for (String key : expandKeys(sourceBucket, List.of(selected))) {
                String destKey = sourceRoot != null
                        ? prefix + base + "/" + key.substring(sourceRoot.length())
                        : prefix + base;
                s3Client.copyObject(CopyObjectRequest.builder()
                        .sourceBucket(sourceBucket)
                        .sourceKey(key)
                        .destinationBucket(targetBucket)
                        .destinationKey(destKey)
                        .build());
                moved.add(key);
            }
        }
        if (deleteSources) {
            deleteObjects(sourceBucket, moved);
        }
        return moved.size();
    }

    public void deleteObjects(String bucket, List<String> keys) {
        for (int i = 0; i < keys.size(); i += 1000) {
            List<ObjectIdentifier> identifiers = keys.subList(i, Math.min(i + 1000, keys.size())).stream()
                    .map(k -> ObjectIdentifier.builder().key(k).build())
                    .collect(Collectors.toList());
            var response = s3Client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder().objects(identifiers).quiet(true).build())
                    .build());
            if (response.hasErrors() && !response.errors().isEmpty()) {
                var err = response.errors().get(0);
                throw new IllegalStateException("Failed to delete '" + err.key() + "': " + err.message());
            }
        }
    }

    public void renameObject(String bucket, String oldKey, String newKey) {
        s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket)
                .sourceKey(oldKey)
                .destinationBucket(bucket)
                .destinationKey(newKey)
                .build());
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(oldKey)
                .build());
    }

    // ── Multipart upload ────────────────────────────────────────────────────

    public String initiateMultipartUpload(String bucket, String key, String contentType) {
        var response = s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build());
        return response.uploadId();
    }

    public String presignUploadPart(String bucket, String key, String uploadId, int partNumber) {
        PresignedUploadPartRequest presigned = s3Presigner.presignUploadPart(
                UploadPartPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(60))
                        .uploadPartRequest(UploadPartRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .build())
                        .build());
        return presigned.url().toString();
    }

    public String uploadPart(String bucket, String key, String uploadId, int partNumber,
                             byte[] partBytes) {
        var response = s3Client.uploadPart(
                UploadPartRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .contentLength((long) partBytes.length)
                        .build(),
                RequestBody.fromBytes(partBytes));
        return response.eTag();
    }

    public void completeMultipartUpload(String bucket, String key, String uploadId,
                                        List<CompleteMultipartRequest.PartETag> parts) {
                List<CompletedPart> completedParts = resolveCompletedParts(bucket, key, uploadId, parts);

                try {
                        completeMultipart(bucket, key, uploadId, completedParts);
                } catch (S3Exception original) {
                        List<CompletedPart> quoteToggledParts = completedParts.stream()
                                        .map(part -> CompletedPart.builder()
                                                        .partNumber(part.partNumber())
                                                        .eTag(toggleQuoteStyle(part.eTag()))
                                                        .build())
                                        .collect(Collectors.toList());

                        if (!quoteToggledParts.equals(completedParts)) {
                                log.warn("CompleteMultipartUpload failed, retrying with alternate ETag quote style. code={}, requestId={}",
                                                original.awsErrorDetails() != null ? original.awsErrorDetails().errorCode() : "unknown",
                                                original.requestId());
                                completeMultipart(bucket, key, uploadId, quoteToggledParts);
                                return;
                        }

                        throw original;
                }
    }

        private List<CompletedPart> resolveCompletedParts(String bucket, String key, String uploadId,
                                                                                                          List<CompleteMultipartRequest.PartETag> clientParts) {
                Map<Integer, String> listedEtags = listUploadedPartEtags(bucket, key, uploadId);

                List<Integer> partNumbers = (clientParts == null ? List.<CompleteMultipartRequest.PartETag>of() : clientParts)
                                .stream()
                                .map(CompleteMultipartRequest.PartETag::getPartNumber)
                                .distinct()
                                .sorted()
                                .collect(Collectors.toList());

                if (partNumbers.isEmpty()) {
                        partNumbers = listedEtags.keySet().stream().sorted().collect(Collectors.toList());
                }

                if (partNumbers.isEmpty()) {
                        throw new IllegalArgumentException("No uploaded multipart parts found for completion");
                }

                return partNumbers.stream()
                                .map(partNumber -> CompletedPart.builder()
                                                .partNumber(partNumber)
                                                .eTag(listedEtags.get(partNumber))
                                                .build())
                                .collect(Collectors.toList());
        }

        private Map<Integer, String> listUploadedPartEtags(String bucket, String key, String uploadId) {
                Map<Integer, String> partEtags = new HashMap<>();
                int marker = 0;

                while (true) {
                        ListPartsResponse response = s3Client.listParts(ListPartsRequest.builder()
                                        .bucket(bucket)
                                        .key(key)
                                        .uploadId(uploadId)
                                        .partNumberMarker(marker)
                                        .build());

                        for (Part part : response.parts()) {
                                partEtags.put(part.partNumber(), part.eTag());
                        }

                        if (!Boolean.TRUE.equals(response.isTruncated())) {
                                break;
                        }

                        Integer nextMarker = response.nextPartNumberMarker();
                        if (nextMarker == null || nextMarker == marker) {
                                break;
                        }
                        marker = nextMarker;
                }

                return partEtags;
        }

        private void completeMultipart(String bucket, String key, String uploadId, List<CompletedPart> completedParts) {
                s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .uploadId(uploadId)
                                .multipartUpload(CompletedMultipartUpload.builder()
                                                .parts(completedParts)
                                                .build())
                                .build());
        }

        private String toggleQuoteStyle(String eTag) {
                if (eTag == null || eTag.isBlank()) {
                        return eTag;
                }

                String trimmed = eTag.trim();
                if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                        return trimmed.substring(1, trimmed.length() - 1);
                }
                return "\"" + trimmed + "\"";
        }

    public void abortMultipartUpload(String bucket, String key, String uploadId) {
        s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .build());
    }

    public Map<String, String> getBucketStats(String bucket) {
        long totalSize = 0;
        long objectCount = 0;

        var paginator = s3Client.listObjectsV2Paginator(
                ListObjectsV2Request.builder().bucket(bucket).build());

        for (var page : paginator) {
            for (S3Object obj : page.contents()) {
                totalSize += obj.size();
                objectCount++;
            }
        }

        return Map.of(
                "objectCount", String.valueOf(objectCount),
                "totalSize", String.valueOf(totalSize)
        );
    }

    // ── Bucket policy ──────────────────────────────────────────────────────

    /** The bucket's policy document as a raw JSON string, or {@code null} when none is set. */
    public String getBucketPolicy(String bucket) {
        try {
            return s3Client.getBucketPolicy(GetBucketPolicyRequest.builder().bucket(bucket).build()).policy();
        } catch (S3Exception ex) {
            if (isS3NotFound(ex, "NoSuchBucketPolicy")) {
                return null;
            }
            throw ex;
        }
    }

    public void putBucketPolicy(String bucket, String policyJson) {
        s3Client.putBucketPolicy(PutBucketPolicyRequest.builder()
                .bucket(bucket)
                .policy(policyJson)
                .build());
    }

    public void deleteBucketPolicy(String bucket) {
        s3Client.deleteBucketPolicy(DeleteBucketPolicyRequest.builder().bucket(bucket).build());
    }

    // ── Bucket CORS ───────────────────────────────────────────────────────

    /** The bucket's CORS rules, or {@code null} when no CORS configuration is set. */
    public List<CorsRuleDto> getBucketCors(String bucket) {
        try {
            return s3Client.getBucketCors(GetBucketCorsRequest.builder().bucket(bucket).build())
                    .corsRules().stream()
                    .map(rule -> new CorsRuleDto(
                            rule.id(),
                            rule.allowedMethods(),
                            rule.allowedOrigins(),
                            rule.allowedHeaders(),
                            rule.exposeHeaders(),
                            rule.maxAgeSeconds()))
                    .toList();
        } catch (S3Exception ex) {
            if (isS3NotFound(ex, "NoSuchCORSConfiguration")) {
                return null;
            }
            throw ex;
        }
    }

    public void putBucketCors(String bucket, List<CorsRuleDto> rules) {
        List<CORSRule> corsRules = rules.stream()
                .map(dto -> CORSRule.builder()
                        .id(dto.id())
                        .allowedMethods(orEmpty(dto.allowedMethods()))
                        .allowedOrigins(orEmpty(dto.allowedOrigins()))
                        .allowedHeaders(orEmpty(dto.allowedHeaders()))
                        .exposeHeaders(orEmpty(dto.exposeHeaders()))
                        .maxAgeSeconds(dto.maxAgeSeconds())
                        .build())
                .toList();
        s3Client.putBucketCors(PutBucketCorsRequest.builder()
                .bucket(bucket)
                .corsConfiguration(CORSConfiguration.builder().corsRules(corsRules).build())
                .build());
    }

    public void deleteBucketCors(String bucket) {
        s3Client.deleteBucketCors(DeleteBucketCorsRequest.builder().bucket(bucket).build());
    }

    // ── Bucket versioning + retention ─────────────────────────────────────

    /** Lifecycle rule ID owned by this app; all other lifecycle rules are left untouched. */
    public static final String RETENTION_RULE_ID = "s3webui-version-retention";

    /** Versioning status ("Enabled", "Suspended" or "Off") plus the retention rule managed by this app, if any. */
    public VersioningConfig getBucketVersioning(String bucket) {
        var response = s3Client.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build());
        String status = response.statusAsString() == null ? "Off" : response.statusAsString();
        Integer maxVersions = null;
        Integer noncurrentDays = null;
        for (LifecycleRule rule : getLifecycleRules(bucket)) {
            if (RETENTION_RULE_ID.equals(rule.id()) && rule.noncurrentVersionExpiration() != null) {
                maxVersions = rule.noncurrentVersionExpiration().newerNoncurrentVersions();
                noncurrentDays = rule.noncurrentVersionExpiration().noncurrentDays();
            }
        }
        return new VersioningConfig(status, maxVersions, noncurrentDays);
    }

    /**
     * Enables or suspends versioning and, when enabled with {@code maxVersions}, keeps only that many
     * noncurrent versions (older ones expire after {@code noncurrentDays}). Other lifecycle rules are preserved.
     */
    public void putBucketVersioning(String bucket, boolean enabled, Integer maxVersions, Integer noncurrentDays) {
        s3Client.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(bucket)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(enabled ? BucketVersioningStatus.ENABLED : BucketVersioningStatus.SUSPENDED)
                        .build())
                .build());

        List<LifecycleRule> rules = new ArrayList<>(getLifecycleRules(bucket));
        boolean hadRule = rules.removeIf(rule -> RETENTION_RULE_ID.equals(rule.id()));
        boolean wantRule = enabled && maxVersions != null;
        if (wantRule) {
            rules.add(LifecycleRule.builder()
                    .id(RETENTION_RULE_ID)
                    .status(ExpirationStatus.ENABLED)
                    .filter(LifecycleRuleFilter.builder().prefix("").build())
                    .noncurrentVersionExpiration(NoncurrentVersionExpiration.builder()
                            .newerNoncurrentVersions(maxVersions)
                            .noncurrentDays(noncurrentDays == null ? 1 : noncurrentDays)
                            .build())
                    .build());
        } else if (!hadRule) {
            return;
        }
        if (rules.isEmpty()) {
            s3Client.deleteBucketLifecycle(DeleteBucketLifecycleRequest.builder().bucket(bucket).build());
        } else {
            s3Client.putBucketLifecycleConfiguration(PutBucketLifecycleConfigurationRequest.builder()
                    .bucket(bucket)
                    .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(rules).build())
                    .build());
        }
    }

    // ── Bucket lifecycle rules ────────────────────────────────────────────

    public List<LifecycleRuleDto> getBucketLifecycle(String bucket) {
        return getLifecycleRules(bucket).stream().map(S3Service::toDto).toList();
    }

    /**
     * Replaces the editable rules with {@code rules}. Rules the editor cannot represent (and the
     * versioning retention rule) are kept exactly as they are on the server.
     */
    public void putBucketLifecycle(String bucket, List<LifecycleRuleDto> rules) {
        List<LifecycleRule> merged = new ArrayList<>();
        for (LifecycleRule existing : getLifecycleRules(bucket)) {
            if (!toDto(existing).editable()) {
                merged.add(existing);
            }
        }
        for (LifecycleRuleDto dto : rules) {
            merged.add(fromDto(dto));
        }
        if (merged.isEmpty()) {
            s3Client.deleteBucketLifecycle(DeleteBucketLifecycleRequest.builder().bucket(bucket).build());
        } else {
            s3Client.putBucketLifecycleConfiguration(PutBucketLifecycleConfigurationRequest.builder()
                    .bucket(bucket)
                    .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(merged).build())
                    .build());
        }
    }

    static LifecycleRuleDto toDto(LifecycleRule rule) {
        boolean editable = rule.id() != null && !rule.id().isBlank() && !RETENTION_RULE_ID.equals(rule.id());
        String prefix = rule.prefix() == null ? "" : rule.prefix();
        LifecycleRuleFilter filter = rule.filter();
        if (filter != null) {
            if (filter.tag() != null || filter.and() != null
                    || filter.objectSizeGreaterThan() != null || filter.objectSizeLessThan() != null) {
                editable = false;
            }
            if (filter.prefix() != null) {
                prefix = filter.prefix();
            }
        }
        Integer expirationDays = null;
        Boolean expireDeleteMarkers = null;
        if (rule.expiration() != null) {
            if (rule.expiration().date() != null) {
                editable = false;
            }
            expirationDays = rule.expiration().days();
            expireDeleteMarkers = rule.expiration().expiredObjectDeleteMarker();
        }
        List<LifecycleRuleDto.Transition> transitions = new ArrayList<>();
        for (Transition t : rule.transitions()) {
            if (t.date() != null || t.days() == null) {
                editable = false;
            }
            transitions.add(new LifecycleRuleDto.Transition(t.days(), t.storageClassAsString()));
        }
        if (!rule.noncurrentVersionTransitions().isEmpty()) {
            editable = false;
        }
        Integer noncurrentDays = null;
        Integer noncurrentKeep = null;
        if (rule.noncurrentVersionExpiration() != null) {
            noncurrentDays = rule.noncurrentVersionExpiration().noncurrentDays();
            noncurrentKeep = rule.noncurrentVersionExpiration().newerNoncurrentVersions();
        }
        Integer abortDays = rule.abortIncompleteMultipartUpload() == null
                ? null : rule.abortIncompleteMultipartUpload().daysAfterInitiation();
        return new LifecycleRuleDto(rule.id(), rule.status() == ExpirationStatus.ENABLED, prefix,
                expirationDays, expireDeleteMarkers, transitions, noncurrentDays, noncurrentKeep,
                abortDays, editable);
    }

    static LifecycleRule fromDto(LifecycleRuleDto dto) {
        LifecycleRule.Builder rule = LifecycleRule.builder()
                .id(dto.id())
                .status(Boolean.FALSE.equals(dto.enabled()) ? ExpirationStatus.DISABLED : ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix(dto.prefix() == null ? "" : dto.prefix()).build());
        boolean hasAction = false;
        boolean deleteMarkers = Boolean.TRUE.equals(dto.expireDeleteMarkers());
        if (dto.expirationDays() != null || deleteMarkers) {
            rule.expiration(LifecycleExpiration.builder()
                    .days(dto.expirationDays())
                    .expiredObjectDeleteMarker(deleteMarkers ? Boolean.TRUE : null)
                    .build());
            hasAction = true;
        }
        if (dto.transitions() != null && !dto.transitions().isEmpty()) {
            rule.transitions(dto.transitions().stream()
                    .map(t -> Transition.builder().days(t.days()).storageClass(t.storageClass()).build())
                    .toList());
            hasAction = true;
        }
        if (dto.noncurrentDays() != null || dto.noncurrentKeepVersions() != null) {
            rule.noncurrentVersionExpiration(NoncurrentVersionExpiration.builder()
                    .noncurrentDays(dto.noncurrentDays())
                    .newerNoncurrentVersions(dto.noncurrentKeepVersions())
                    .build());
            hasAction = true;
        }
        if (dto.abortIncompleteMultipartDays() != null) {
            rule.abortIncompleteMultipartUpload(AbortIncompleteMultipartUpload.builder()
                    .daysAfterInitiation(dto.abortIncompleteMultipartDays()).build());
            hasAction = true;
        }
        if (!hasAction) {
            throw new IllegalArgumentException("Lifecycle rule '" + dto.id() + "' needs at least one action");
        }
        return rule.build();
    }

    private List<LifecycleRule> getLifecycleRules(String bucket) {
        try {
            return s3Client.getBucketLifecycleConfiguration(
                    GetBucketLifecycleConfigurationRequest.builder().bucket(bucket).build()).rules();
        } catch (S3Exception ex) {
            if (isS3NotFound(ex, "NoSuchLifecycleConfiguration")) {
                return List.of();
            }
            throw ex;
        }
    }

    public record VersioningConfig(String status, Integer maxVersions, Integer noncurrentDays) {
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static boolean isS3NotFound(S3Exception ex, String errorCode) {
        return ex.awsErrorDetails() != null && errorCode.equals(ex.awsErrorDetails().errorCode());
    }
}
