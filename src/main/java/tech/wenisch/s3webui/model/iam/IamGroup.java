package tech.wenisch.s3webui.model.iam;

import java.time.Instant;

public record IamGroup(String groupName, String arn, Instant createdAt) {
}
