package tech.wenisch.s3webui.model.iam;

/**
 * What the UI may show. Backends implement very different subsets of the IAM API - AWS has all
 * of it, Ceph RGW has users, groups and inline policies but no standalone policy CRUD, MinIO has
 * none of it - so each capability is probed rather than assumed.
 *
 * @param available       whether any IAM operation can be attempted at all
 * @param provider        a display name for the backing implementation ("AWS IAM", "MinIO")
 * @param reason          why it is unavailable, for the UI to show; null when available
 * @param groups          the provider supports groups and group membership
 * @param accessKeys      the provider can mint additional access keys for an existing user
 * @param managedPolicies standalone policies can be listed, created, edited and deleted. False on
 *                        providers that only offer a fixed catalogue to attach from
 * @param inlinePolicies  policy documents can be embedded directly on a user or group
 */
public record IamCapabilities(
        boolean available,
        String provider,
        String reason,
        boolean groups,
        boolean accessKeys,
        boolean managedPolicies,
        boolean inlinePolicies) {

    public static IamCapabilities unavailable(String reason) {
        return new IamCapabilities(false, null, reason, false, false, false, false);
    }
}
