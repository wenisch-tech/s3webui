package tech.wenisch.s3webui.model.iam;

/**
 * What the UI may show. {@code available} is false when the feature flag is off, when no IAM
 * provider is configured, or when a probe against the active S3 key showed the endpoint does not
 * answer IAM calls at all.
 *
 * @param available   whether any IAM operation can be attempted
 * @param provider    a display name for the backing implementation ("AWS IAM", "MinIO")
 * @param reason      why it is unavailable, for the UI to show; null when available
 * @param groups      whether the provider supports groups
 * @param accessKeys  whether the provider can mint additional access keys for an existing user
 */
public record IamCapabilities(
        boolean available,
        String provider,
        String reason,
        boolean groups,
        boolean accessKeys) {

    public static IamCapabilities unavailable(String reason) {
        return new IamCapabilities(false, null, reason, false, false);
    }
}
