package tech.wenisch.s3webui.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class AuditEvent {
    Instant timestamp;
    String user;
    String action;
    String resourceType;
    String bucket;
    String key;
    String details;
}