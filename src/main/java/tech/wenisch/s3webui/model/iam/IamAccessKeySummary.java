package tech.wenisch.s3webui.model.iam;

import java.time.Instant;

/**
 * A credential belonging to an IAM user.
 *
 * <p>On AWS every entry is an independently deletable access key. RustFS models the user's own
 * credential as its primary key and additional credentials as service accounts, so callers need
 * to know that the primary entry can only be removed by deleting the user.</p>
 */
public record IamAccessKeySummary(
        String accessKeyId,
        String status,
        Instant createdAt,
        boolean primary,
        boolean deletable) {
}
