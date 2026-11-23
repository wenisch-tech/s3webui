package tech.wenisch.s3webui.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.s3webui.model.ConflictPolicy;
import tech.wenisch.s3webui.model.MigrationJobView;
import tech.wenisch.s3webui.service.MigrationService;
import tech.wenisch.s3webui.service.MigrationService.SubmitCommand;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService;

import java.util.List;

/**
 * Starts and follows server-side migrations between S3 keys. The source is always the key the user
 * currently has selected; the destination key is named in the request and never replaces the session
 * selection.
 */
@RestController
@RequestMapping("/api/migrations")
@RequiredArgsConstructor
public class MigrationApiController {

    private final MigrationService migrationService;
    private final S3ConnectionSettingsService s3ConnectionSettingsService;

    /** {@code keys} empty means everything below {@code sourcePrefix}; folder keys end in a slash. */
    public record SubmitRequest(
            String sourceBucket,
            String sourcePrefix,
            List<String> keys,
            String targetCredentialId,
            String targetBucket,
            String targetPrefix,
            String conflictPolicy,
            boolean deleteSource
    ) {
    }

    @PostMapping
    public ResponseEntity<MigrationJobView> submit(@RequestBody SubmitRequest request) {
        ConflictPolicy policy = parsePolicy(request.conflictPolicy());
        MigrationJobView job = migrationService.submit(new SubmitCommand(
                s3ConnectionSettingsService.getActiveCredentialId(),
                request.sourceBucket(),
                request.sourcePrefix(),
                request.keys(),
                request.targetCredentialId(),
                request.targetBucket(),
                request.targetPrefix(),
                policy,
                request.deleteSource()), authentication());
        return ResponseEntity.accepted().body(job);
    }

    @GetMapping
    public List<MigrationJobView> list() {
        return migrationService.list(authentication());
    }

    @GetMapping("/{id}")
    public MigrationJobView get(@PathVariable String id) {
        return migrationService.get(id, authentication());
    }

    @PostMapping("/{id}/cancel")
    public MigrationJobView cancel(@PathVariable String id) {
        return migrationService.cancel(id, authentication());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> dismiss(@PathVariable String id) {
        migrationService.dismiss(id, authentication());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/targets/{credentialId}/buckets")
    public List<String> targetBuckets(@PathVariable String credentialId) {
        return migrationService.listTargetBuckets(authentication(), credentialId);
    }

    @GetMapping("/targets/{credentialId}/buckets/{bucket}/folders")
    public List<String> targetFolders(@PathVariable String credentialId,
                                      @PathVariable String bucket,
                                      @RequestParam(defaultValue = "") String prefix) {
        return migrationService.listTargetFolders(authentication(), credentialId, bucket, prefix);
    }

    private static ConflictPolicy parsePolicy(String value) {
        if (value == null || value.isBlank()) {
            return ConflictPolicy.SKIP;
        }
        try {
            return ConflictPolicy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown conflict policy '" + value + "'");
        }
    }

    private static Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
