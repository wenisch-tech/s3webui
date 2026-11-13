package tech.wenisch.s3webui.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import tech.wenisch.s3webui.model.CompleteMultipartRequest;
import tech.wenisch.s3webui.model.BucketDto;
import tech.wenisch.s3webui.model.CorsRuleDto;
import tech.wenisch.s3webui.model.LifecycleRuleDto;
import tech.wenisch.s3webui.model.ObjectVersionDto;
import tech.wenisch.s3webui.model.MoveObjectsRequest;
import tech.wenisch.s3webui.model.S3ObjectDto;
import tech.wenisch.s3webui.service.AuditHistoryService;
import tech.wenisch.s3webui.service.BucketVisibilityService;
import tech.wenisch.s3webui.service.GlobalSearchService;
import tech.wenisch.s3webui.service.S3Service;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class S3ApiController {

    private final S3Service s3Service;
    private final AuditHistoryService auditHistoryService;
    private final JsonMapper jsonMapper;
    private GlobalSearchService globalSearchService;

    @Autowired
    void setGlobalSearchService(GlobalSearchService globalSearchService) {
        this.globalSearchService = globalSearchService;
    }

    private BucketVisibilityService bucketVisibilityService;

    @Autowired
    void setBucketVisibilityService(BucketVisibilityService bucketVisibilityService) {
        this.bucketVisibilityService = bucketVisibilityService;
    }

    // ── Buckets ────────────────────────────────────────────────────────────

    @PostMapping("/buckets")
    public ResponseEntity<Void> createBucket(@RequestParam String name, Principal principal) {
        try {
            s3Service.createBucket(name);
            invalidateSearchCatalog();
            auditHistoryService.record(username(principal), "CREATE", "BUCKET", name, null, "Created bucket");
        } catch (Exception ex) {
            recordFailure(principal, "CREATE", "BUCKET", name, null, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/buckets/{bucket}")
    public ResponseEntity<Void> deleteBucket(
            @PathVariable String bucket,
            @RequestParam(required = false, defaultValue = "false") boolean force,
            Principal principal) {
        try {
            if (force) {
                s3Service.deleteBucketWithContents(bucket);
            } else {
                s3Service.deleteBucket(bucket);
            }
            invalidateSearchCatalog();
            auditHistoryService.record(username(principal), "DELETE", "BUCKET", bucket, null,
                    force ? "Deleted bucket including all contents" : "Deleted bucket");
        } catch (Exception ex) {
            recordFailure(principal, "DELETE", "BUCKET", bucket, null, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    // ── Objects ────────────────────────────────────────────────────────────

    @GetMapping("/buckets/{bucket}/objects")
    public ResponseEntity<List<S3ObjectDto>> listObjects(
            @PathVariable String bucket,
            @RequestParam(required = false, defaultValue = "") String prefix) {
        return ResponseEntity.ok(s3Service.listObjects(bucket, prefix));
    }

    @GetMapping("/buckets/{bucket}/objects/search")
    public ResponseEntity<GlobalSearchService.BucketSearchPage> searchObjects(
            @PathVariable String bucket,
            @RequestParam(name = "q") String query,
            @RequestParam(required = false, defaultValue = "") String prefix) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(globalSearchService.searchBucket(bucket, prefix, query));
    }

    @DeleteMapping("/buckets/{bucket}/objects")
    public ResponseEntity<Void> deleteObject(
            @PathVariable String bucket,
            @RequestParam String key,
            Principal principal) {
        try {
            s3Service.deleteObject(bucket, key);
            invalidateSearchCatalog();
            auditHistoryService.record(username(principal), "DELETE", "OBJECT", bucket, key, "Deleted object");
        } catch (Exception ex) {
            recordFailure(principal, "DELETE", "OBJECT", bucket, key, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/buckets/{bucket}/objects/delete")
    public ResponseEntity<Void> deleteObjects(
            @PathVariable String bucket,
            @RequestBody List<String> keys,
            Principal principal) {
        if (keys == null || keys.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            s3Service.deleteKeys(bucket, keys);
            invalidateSearchCatalog();
            for (String key : keys) {
                auditHistoryService.record(username(principal), "DELETE", "OBJECT", bucket, key, "Deleted object");
            }
        } catch (Exception ex) {
            recordFailure(principal, "DELETE", "OBJECT", bucket, null, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/buckets/{bucket}/objects/move")
    public ResponseEntity<Void> moveObjects(
            @PathVariable String bucket,
            @RequestBody MoveObjectsRequest request,
            Principal principal) {
        if (request.getKeys() == null || request.getKeys().isEmpty()
                || request.getTargetBucket() == null || request.getTargetBucket().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        boolean copy = request.isCopy();
        String action = copy ? "COPY" : "MOVE";
        String detail = (copy ? "Copied to " : "Moved to ") + request.getTargetBucket() + "/"
                + (request.getTargetPrefix() == null ? "" : request.getTargetPrefix());
        try {
            if (copy) {
                s3Service.copyObjects(bucket, request.getKeys(), request.getTargetBucket(), request.getTargetPrefix());
            } else {
                s3Service.moveObjects(bucket, request.getKeys(), request.getTargetBucket(), request.getTargetPrefix());
            }
            invalidateSearchCatalog();
            for (String key : request.getKeys()) {
                auditHistoryService.record(username(principal), action, "OBJECT", bucket, key, detail);
            }
        } catch (Exception ex) {
            recordFailure(principal, action, "OBJECT", bucket, null, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    /**
     * The session's buckets. Filtered like the bucket page when an admin hides unbrowsable buckets;
     * {@code includeUnbrowsable=true} returns the backend's full list (the IAM bucket-access picker
     * needs every bucket to grant access to).
     */
    @GetMapping("/buckets")
    public ResponseEntity<List<BucketDto>> listBuckets(
            @RequestParam(required = false, defaultValue = "false") boolean includeUnbrowsable) {
        List<BucketDto> buckets = s3Service.listBuckets();
        if (!includeUnbrowsable && bucketVisibilityService != null) {
            buckets = bucketVisibilityService.filter(buckets);
        }
        return ResponseEntity.ok(buckets);
    }

    @PostMapping("/buckets/{bucket}/objects/rename")
    public ResponseEntity<Void> renameObject(
            @PathVariable String bucket,
            @RequestParam String oldKey,
            @RequestParam String newKey,
            Principal principal) {
        try {
            s3Service.renameObject(bucket, oldKey, newKey);
            invalidateSearchCatalog();
            auditHistoryService.record(username(principal), "EDIT", "OBJECT", bucket, newKey,
                    "Renamed object from '" + oldKey + "' to '" + newKey + "'");
        } catch (Exception ex) {
            recordFailure(principal, "EDIT", "OBJECT", bucket, newKey, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/buckets/{bucket}/objects/download")
    public void downloadObject(
            @PathVariable String bucket,
            @RequestParam String key,
            @RequestParam(required = false) String versionId,
            HttpServletResponse response) throws IOException {
        String filename = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + URLEncoder.encode(filename, StandardCharsets.UTF_8) + "\"");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);

        try (ResponseInputStream<GetObjectResponse> s3Stream = s3Service.getObject(bucket, key, versionId)) {
            GetObjectResponse meta = s3Stream.response();
            if (meta.contentLength() != null) {
                response.setContentLengthLong(meta.contentLength());
            }
            s3Stream.transferTo(response.getOutputStream());
        }
    }

    // ── Object versions ───────────────────────────────────────────────────

    @GetMapping("/buckets/{bucket}/objects/versions")
    public List<ObjectVersionDto> listObjectVersions(@PathVariable String bucket, @RequestParam String key) {
        return s3Service.listObjectVersions(bucket, key);
    }

    @PostMapping("/buckets/{bucket}/objects/versions/restore")
    public ResponseEntity<Void> restoreObjectVersion(
            @PathVariable String bucket,
            @RequestParam String key,
            @RequestParam String versionId,
            Principal principal) {
        try {
            s3Service.restoreObjectVersion(bucket, key, versionId);
            invalidateSearchCatalog();
            auditHistoryService.record(username(principal), "EDIT", "OBJECT", bucket, key,
                    "Restored version " + versionId);
        } catch (Exception ex) {
            recordFailure(principal, "EDIT", "OBJECT", bucket, key, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/buckets/{bucket}/objects/versions")
    public ResponseEntity<Void> deleteObjectVersion(
            @PathVariable String bucket,
            @RequestParam String key,
            @RequestParam String versionId,
            Principal principal) {
        try {
            s3Service.deleteObjectVersion(bucket, key, versionId);
            invalidateSearchCatalog();
            auditHistoryService.record(username(principal), "DELETE", "OBJECT", bucket, key,
                    "Permanently deleted version " + versionId);
        } catch (Exception ex) {
            recordFailure(principal, "DELETE", "OBJECT", bucket, key, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    // ── Simple upload (for small files or fallback) ────────────────────────

    @PostMapping("/buckets/{bucket}/objects/upload")
    public ResponseEntity<Void> uploadObject(
            @PathVariable String bucket,
            @RequestParam String prefix,
            @RequestParam("file") MultipartFile file,
            Principal principal) throws IOException {
        String key = (prefix == null || prefix.isBlank())
                ? file.getOriginalFilename()
                : (prefix.endsWith("/") ? prefix : prefix + "/") + file.getOriginalFilename();
        try {
            s3Service.putObject(bucket, key, file.getInputStream(),
                file.getSize(), file.getContentType());
            invalidateSearchCatalog();
            auditHistoryService.record(username(principal), "CREATE", "OBJECT", bucket, key, "Uploaded object");
        } catch (Exception ex) {
            recordFailure(principal, "CREATE", "OBJECT", bucket, key, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    // ── Multipart upload (client-side) ─────────────────────────────────────

    @PostMapping("/buckets/{bucket}/multipart/initiate")
    public ResponseEntity<Map<String, String>> initiateMultipart(
            @PathVariable String bucket,
            @RequestParam String key,
            @RequestParam(defaultValue = "application/octet-stream") String contentType) {
        String uploadId = s3Service.initiateMultipartUpload(bucket, key, contentType);
        return ResponseEntity.ok(Map.of("uploadId", uploadId));
    }

    @GetMapping("/buckets/{bucket}/multipart/presign")
    public ResponseEntity<Map<String, String>> presignPart(
            @PathVariable String bucket,
            @RequestParam String key,
            @RequestParam String uploadId,
            @RequestParam int partNumber) {
        String url = s3Service.presignUploadPart(bucket, key, uploadId, partNumber);
        return ResponseEntity.ok(Map.of("url", url));
    }

        // Maximum part size: 5GB (S3 part limit is 5TB, but we limit for DoS protection)
    private static final long MAX_PART_SIZE = 5 * 1024 * 1024 * 1024L; // 5GB

    @PutMapping(value = "/buckets/{bucket}/multipart/part",
                consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Map<String, String>> uploadMultipartPart(
            @PathVariable String bucket,
            @RequestParam String key,
            @RequestParam String uploadId,
            @RequestParam int partNumber,
            HttpServletRequest request) throws IOException {
        // CVE-2026-22732: Validate Content-Length to prevent unbounded memory reads
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_PART_SIZE) {
            return ResponseEntity.badRequest().build();
        }
        if (contentLength < 0) {
            return ResponseEntity.badRequest().build();
        }

        byte[] partBytes = request.getInputStream().readNBytes((int)contentLength);
        String eTag = s3Service.uploadPart(bucket, key, uploadId, partNumber, partBytes);
        return ResponseEntity.ok(Map.of("eTag", eTag));
    }

    @PostMapping("/buckets/{bucket}/multipart/complete")
    public ResponseEntity<Void> completeMultipart(
            @PathVariable String bucket,
            @RequestParam String key,
            @RequestBody CompleteMultipartRequest request,
            Principal principal) {
        request.setKey(key);
        try {
            s3Service.completeMultipartUpload(bucket, key, request.getUploadId(), request.getParts());
            invalidateSearchCatalog();
            auditHistoryService.record(username(principal), "CREATE", "OBJECT", bucket, key,
                    "Completed multipart upload");
        } catch (Exception ex) {
            recordFailure(principal, "CREATE", "OBJECT", bucket, key, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/buckets/{bucket}/multipart/abort")
    public ResponseEntity<Void> abortMultipart(
            @PathVariable String bucket,
            @RequestParam String key,
            @RequestParam String uploadId) {
        try {
            s3Service.abortMultipartUpload(bucket, key, uploadId);
        } catch (Exception ex) {
            // No principal is available on this endpoint, keep actor resolution consistent via null.
            recordFailure(null, "DELETE", "OBJECT", bucket, key, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    // ── Stats ──────────────────────────────────────────────────────────────

    @GetMapping("/buckets/{bucket}/stats")
    public ResponseEntity<Map<String, String>> getBucketStats(@PathVariable String bucket) {
        return ResponseEntity.ok(s3Service.getBucketStats(bucket));
    }

    // ── Bucket policy ──────────────────────────────────────────────────────

    @GetMapping("/buckets/{bucket}/policy")
    public Map<String, String> getBucketPolicy(@PathVariable String bucket) {
        Map<String, String> body = new HashMap<>();
        body.put("policy", s3Service.getBucketPolicy(bucket));
        return body;
    }

    @PutMapping("/buckets/{bucket}/policy")
    public ResponseEntity<Void> updateBucketPolicy(
            @PathVariable String bucket,
            @RequestBody String policyJson,
            Principal principal) {
        requireValidJson(policyJson, "Bucket policy");
        try {
            s3Service.putBucketPolicy(bucket, policyJson);
            invalidateBucketVisibility();
            auditHistoryService.record(username(principal), "EDIT", "BUCKET", bucket, null, "Updated bucket policy");
        } catch (Exception ex) {
            recordFailure(principal, "EDIT", "BUCKET", bucket, null, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/buckets/{bucket}/policy")
    public ResponseEntity<Void> deleteBucketPolicy(@PathVariable String bucket, Principal principal) {
        try {
            s3Service.deleteBucketPolicy(bucket);
            invalidateBucketVisibility();
            auditHistoryService.record(username(principal), "DELETE", "BUCKET", bucket, null, "Removed bucket policy");
        } catch (Exception ex) {
            recordFailure(principal, "DELETE", "BUCKET", bucket, null, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    // ── Bucket CORS ───────────────────────────────────────────────────────

    @GetMapping("/buckets/{bucket}/cors")
    public CorsConfigResponse getBucketCors(@PathVariable String bucket) {
        return new CorsConfigResponse(s3Service.getBucketCors(bucket));
    }

    @PutMapping("/buckets/{bucket}/cors")
    public ResponseEntity<Void> updateBucketCors(
            @PathVariable String bucket,
            @RequestBody CorsConfigRequest request,
            Principal principal) {
        if (request == null || request.corsRules() == null || request.corsRules().isEmpty()) {
            throw new IllegalArgumentException("At least one CORS rule is required (use DELETE to clear the configuration)");
        }
        try {
            s3Service.putBucketCors(bucket, request.corsRules());
            auditHistoryService.record(username(principal), "EDIT", "BUCKET", bucket, null, "Updated CORS configuration");
        } catch (Exception ex) {
            recordFailure(principal, "EDIT", "BUCKET", bucket, null, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/buckets/{bucket}/cors")
    public ResponseEntity<Void> deleteBucketCors(@PathVariable String bucket, Principal principal) {
        try {
            s3Service.deleteBucketCors(bucket);
            auditHistoryService.record(username(principal), "DELETE", "BUCKET", bucket, null, "Removed CORS configuration");
        } catch (Exception ex) {
            recordFailure(principal, "DELETE", "BUCKET", bucket, null, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    // ── Bucket versioning ─────────────────────────────────────────────────

    @GetMapping("/buckets/{bucket}/versioning")
    public S3Service.VersioningConfig getBucketVersioning(@PathVariable String bucket) {
        return s3Service.getBucketVersioning(bucket);
    }

    @PutMapping("/buckets/{bucket}/versioning")
    public ResponseEntity<Void> updateBucketVersioning(
            @PathVariable String bucket,
            @RequestBody VersioningRequest request,
            Principal principal) {
        if (request == null || request.enabled() == null) {
            throw new IllegalArgumentException("'enabled' is required");
        }
        if (request.maxVersions() != null && request.maxVersions() < 1) {
            throw new IllegalArgumentException("Versions to keep must be at least 1");
        }
        if (request.noncurrentDays() != null && request.noncurrentDays() < 1) {
            throw new IllegalArgumentException("Days must be at least 1");
        }
        try {
            s3Service.putBucketVersioning(bucket, request.enabled(), request.maxVersions(), request.noncurrentDays());
            auditHistoryService.record(username(principal), "EDIT", "BUCKET", bucket, null, "Updated versioning configuration");
        } catch (Exception ex) {
            recordFailure(principal, "EDIT", "BUCKET", bucket, null, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    // ── Bucket lifecycle ──────────────────────────────────────────────────

    @GetMapping("/buckets/{bucket}/lifecycle")
    public LifecycleConfig getBucketLifecycle(@PathVariable String bucket) {
        return new LifecycleConfig(s3Service.getBucketLifecycle(bucket));
    }

    @PutMapping("/buckets/{bucket}/lifecycle")
    public ResponseEntity<Void> updateBucketLifecycle(
            @PathVariable String bucket,
            @RequestBody LifecycleConfig request,
            Principal principal) {
        List<LifecycleRuleDto> rules = request == null || request.rules() == null ? List.of() : request.rules();
        validateLifecycleRules(rules);
        try {
            s3Service.putBucketLifecycle(bucket, rules);
            auditHistoryService.record(username(principal), "EDIT", "BUCKET", bucket, null, "Updated lifecycle rules");
        } catch (Exception ex) {
            recordFailure(principal, "EDIT", "BUCKET", bucket, null, ex);
            throw ex;
        }
        return ResponseEntity.ok().build();
    }

    private static final Set<String> TRANSITION_CLASSES = Set.of(
            "STANDARD_IA", "ONEZONE_IA", "INTELLIGENT_TIERING", "GLACIER_IR", "GLACIER", "DEEP_ARCHIVE");

    private static void validateLifecycleRules(List<LifecycleRuleDto> rules) {
        Set<String> ids = new HashSet<>();
        for (LifecycleRuleDto rule : rules) {
            if (rule == null || rule.id() == null || rule.id().isBlank()) {
                throw new IllegalArgumentException("Every lifecycle rule needs an ID");
            }
            if (rule.id().length() > 255 || !ids.add(rule.id())) {
                throw new IllegalArgumentException("Lifecycle rule IDs must be unique and at most 255 characters: " + rule.id());
            }
            if (S3Service.RETENTION_RULE_ID.equals(rule.id())) {
                throw new IllegalArgumentException("'" + rule.id() + "' is managed by the Versioning dialog");
            }
            requirePositive(rule.expirationDays(), "Expiration days");
            requirePositive(rule.noncurrentDays(), "Noncurrent version days");
            requirePositive(rule.noncurrentKeepVersions(), "Noncurrent versions to keep");
            requirePositive(rule.abortIncompleteMultipartDays(), "Abort incomplete uploads days");
            if (rule.expirationDays() != null && Boolean.TRUE.equals(rule.expireDeleteMarkers())) {
                throw new IllegalArgumentException(
                        "Rule '" + rule.id() + "': expiring after N days cannot be combined with removing expired delete markers");
            }
            if (rule.transitions() != null) {
                for (LifecycleRuleDto.Transition t : rule.transitions()) {
                    requirePositive(t.days() == null ? 0 : t.days(), "Transition days");
                    if (t.storageClass() == null || !TRANSITION_CLASSES.contains(t.storageClass())) {
                        throw new IllegalArgumentException("Unsupported storage class: " + t.storageClass());
                    }
                }
            }
        }
    }

    private static void requirePositive(Integer value, String what) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(what + " must be at least 1");
        }
    }

    public record LifecycleConfig(List<LifecycleRuleDto> rules) {
    }

    public record VersioningRequest(Boolean enabled, Integer maxVersions, Integer noncurrentDays) {
    }

    /** Rejects a body that is not well-formed JSON with a 400 before it ever reaches S3. */
    private void requireValidJson(String json, String what) {
        try {
            jsonMapper.readTree(json);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException(what + " is not valid JSON: " + ex.getOriginalMessage());
        }
    }

    /** Mirrors {@code aws s3api put-bucket-cors}: {@code {"CORSRules":[ ... ]}}. */
    public record CorsConfigRequest(@JsonProperty("CORSRules") List<CorsRuleDto> corsRules) {
    }

    public record CorsConfigResponse(@JsonProperty("CORSRules") List<CorsRuleDto> corsRules) {
    }

    private String username(Principal principal) {
        return principal == null ? null : principal.getName();
    }

    private void invalidateBucketVisibility() {
        if (bucketVisibilityService != null) {
            bucketVisibilityService.invalidate();
        }
    }

    private void invalidateSearchCatalog() {
        if (globalSearchService != null) {
            globalSearchService.invalidate();
        }
    }

    private void recordFailure(
            Principal principal,
            String action,
            String resourceType,
            String bucket,
            String key,
            Throwable ex) {
        String message = resolveMessage(ex);
        auditHistoryService.record(
                username(principal),
                action,
                resourceType,
                bucket,
                key,
                "Failed: " + message);
    }

    private String resolveMessage(Throwable ex) {
        if (ex instanceof S3Exception s3ex
                && s3ex.awsErrorDetails() != null
                && s3ex.awsErrorDetails().errorMessage() != null
                && !s3ex.awsErrorDetails().errorMessage().isBlank()) {
            return s3ex.awsErrorDetails().errorMessage();
        }

        String message = ex.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }

        Throwable cause = ex.getCause();
        if (cause != null && cause != ex && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }

        return ex.getClass().getSimpleName();
    }
}
