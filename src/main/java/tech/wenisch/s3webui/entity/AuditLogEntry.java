package tech.wenisch.s3webui.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persisted form of an audit entry. Exposed to the UI as {@code model.AuditEvent}.
 */
@Entity
@Table(name = "audit_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_timestamp")
    private Instant timestamp;

    /** E-mail of the acting user, or {@code anonymous}. */
    private String userEmail;

    /** Name of the S3 key the action was performed with. */
    private String credentialName;

    private String action;

    private String resourceType;

    private String bucket;

    @Column(name = "object_key", length = 2048)
    private String objectKey;

    @Column(length = 2048)
    private String details;
}
