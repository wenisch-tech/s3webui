package tech.wenisch.s3webui.controller;

import tech.wenisch.s3webui.model.CompleteMultipartRequest;
import tech.wenisch.s3webui.model.S3ObjectDto;
import tech.wenisch.s3webui.service.AuditHistoryService;
import tech.wenisch.s3webui.service.S3Service;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class S3ApiController {

    private final S3Service s3Service;
    private final AuditHistoryService auditHistoryService;

    // ── Buckets ────────────────────────────────────────────────────────────

    @PostMapping("/buckets")
    public ResponseEntity<Void> createBucket(@RequestParam String name, Principal principal) {
        s3Service.createBucket(name);
        auditHistoryService.record(username(principal), "CREATE", "BUCKET", name, null, "Created bucket");
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/buckets/{bucket}")
    public ResponseEntity<Void> deleteBucket(@PathVariable String bucket, Principal principal) {
        s3Service.deleteBucket(bucket);
        auditHistoryService.record(username(principal), "DELETE", "BUCKET", bucket, null, "Deleted bucket");
        return ResponseEntity.ok().build();
    }

    // ── Objects ────────────────────────────────────────────────────────────

    @GetMapping("/buckets/{bucket}/objects")
    public ResponseEntity<List<S3ObjectDto>> listObjects(
            @PathVariable String bucket,
            @RequestParam(required = false, defaultValue = "") String prefix) {
        return ResponseEntity.ok(s3Service.listObjects(bucket, prefix));
    }

    @DeleteMapping("/buckets/{bucket}/objects")
    public ResponseEntity<Void> deleteObject(
            @PathVariable String bucket,
            @RequestParam String key,
            Principal principal) {
        s3Service.deleteObject(bucket, key);
        auditHistoryService.record(username(principal), "DELETE", "OBJECT", bucket, key, "Deleted object");
        return ResponseEntity.ok().build();
    }

    @PostMapping("/buckets/{bucket}/objects/rename")
    public ResponseEntity<Void> renameObject(
            @PathVariable String bucket,
            @RequestParam String oldKey,
            @RequestParam String newKey,
            Principal principal) {
        s3Service.renameObject(bucket, oldKey, newKey);
        auditHistoryService.record(username(principal), "EDIT", "OBJECT", bucket, newKey,
                "Renamed object from '" + oldKey + "' to '" + newKey + "'");
        return ResponseEntity.ok().build();
    }

    @GetMapping("/buckets/{bucket}/objects/download")
    public void downloadObject(
            @PathVariable String bucket,
            @RequestParam String key,
            HttpServletResponse response) throws IOException {
        String filename = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + URLEncoder.encode(filename, StandardCharsets.UTF_8) + "\"");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);

        try (ResponseInputStream<GetObjectResponse> s3Stream = s3Service.getObject(bucket, key)) {
            GetObjectResponse meta = s3Stream.response();
            if (meta.contentLength() != null) {
                response.setContentLengthLong(meta.contentLength());
            }
            s3Stream.transferTo(response.getOutputStream());
        }
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
        s3Service.putObject(bucket, key, file.getInputStream(),
                file.getSize(), file.getContentType());
        auditHistoryService.record(username(principal), "CREATE", "OBJECT", bucket, key, "Uploaded object");
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
        s3Service.completeMultipartUpload(bucket, key, request.getUploadId(), request.getParts());
        auditHistoryService.record(username(principal), "CREATE", "OBJECT", bucket, key,
                "Completed multipart upload");
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/buckets/{bucket}/multipart/abort")
    public ResponseEntity<Void> abortMultipart(
            @PathVariable String bucket,
            @RequestParam String key,
            @RequestParam String uploadId) {
        s3Service.abortMultipartUpload(bucket, key, uploadId);
        return ResponseEntity.ok().build();
    }

    // ── Stats ──────────────────────────────────────────────────────────────

    @GetMapping("/buckets/{bucket}/stats")
    public ResponseEntity<Map<String, String>> getBucketStats(@PathVariable String bucket) {
        return ResponseEntity.ok(s3Service.getBucketStats(bucket));
    }

    private String username(Principal principal) {
        return principal == null ? null : principal.getName();
    }
}
