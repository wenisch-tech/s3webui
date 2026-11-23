package tech.wenisch.s3webui.model;

import java.time.Instant;
import java.util.List;

/** What the UI sees of a migration job. Never carries credentials. */
public record MigrationJobView(
        String id,
        String owner,
        MigrationStatus status,
        boolean active,
        String sourceCredentialName,
        String sourceBucket,
        String sourcePrefix,
        List<String> keys,
        String targetCredentialName,
        String targetBucket,
        String targetPrefix,
        ConflictPolicy conflictPolicy,
        boolean deleteSource,
        long totalObjects,
        long totalBytes,
        long copiedObjects,
        long copiedBytes,
        long skippedObjects,
        long failedObjects,
        String currentKey,
        String lastError,
        List<String> errors,
        Instant createdAt,
        Instant startedAt,
        Instant updatedAt,
        Instant finishedAt
) {
}
