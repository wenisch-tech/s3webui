package tech.wenisch.s3webui.model.iam;

import java.time.Instant;

public record IamUser(String userName, String arn, Instant createdAt) {
}
