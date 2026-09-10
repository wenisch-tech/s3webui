package tech.wenisch.s3webui.model.iam;

import java.time.Instant;

public record IamAccessKeySummary(String accessKeyId, String status, Instant createdAt) {
}
