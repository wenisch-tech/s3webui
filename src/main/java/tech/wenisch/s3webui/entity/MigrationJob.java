package tech.wenisch.s3webui.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tech.wenisch.s3webui.model.ConflictPolicy;
import tech.wenisch.s3webui.model.MigrationStatus;

import java.time.Instant;

/**
 * A server-side copy of buckets or objects from one S3 key to another.
 *
 * <p>Only key ids are stored, never secrets: a resumed job resolves its keys again.
 */
@Entity
@Table(name = "migration_job")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationJob {

    @Id
    @Column(length = 36)
    private String id;

    /** E-mail (or principal name) of the user who started the job. */
    @Column(nullable = false)
    private String ownerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MigrationStatus status;

    private String sourceCredentialId;
    private String sourceCredentialName;
    private String sourceBucket;

    @Column(length = 2048)
    private String sourcePrefix;

    /** JSON array of the selected keys; empty means everything below the prefix. */
    @Column(columnDefinition = "text")
    private String selectedKeys;

    private String targetCredentialId;
    private String targetCredentialName;
    private String targetBucket;

    @Column(length = 2048)
    private String targetPrefix;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConflictPolicy conflictPolicy;

    private boolean deleteSource;

    private long totalObjects;
    private long totalBytes;
    private long copiedObjects;
    private long copiedBytes;
    private long skippedObjects;
    private long failedObjects;

    @Column(length = 2048)
    private String currentKey;

    @Column(length = 2048)
    private String lastError;

    /** JSON array with the most recent per-object errors. */
    @Column(columnDefinition = "text")
    private String errorLog;

    private Instant createdAt;
    private Instant startedAt;
    private Instant updatedAt;
    private Instant finishedAt;
}
