package tech.wenisch.s3webui.service.iam;

import tech.wenisch.s3webui.model.iam.IamAccessKey;
import tech.wenisch.s3webui.model.iam.IamAccessKeySummary;
import tech.wenisch.s3webui.model.iam.IamCapabilities;
import tech.wenisch.s3webui.model.iam.IamGroup;
import tech.wenisch.s3webui.model.iam.IamPolicySummary;
import tech.wenisch.s3webui.model.iam.IamTarget;
import tech.wenisch.s3webui.model.iam.IamUser;

import java.util.List;

/**
 * Identity management for the storage backend, abstracted over the wire protocol.
 *
 * <p>AWS and MinIO disagree on almost everything here: AWS speaks the IAM API and identifies
 * policies by ARN, MinIO speaks its own admin API and identifies them by name. Implementations
 * therefore take an opaque {@code policyId} that is whatever that provider round-trips, and
 * declare what they can do through {@link #capabilities()}.
 *
 * <p>Implementations are request-scoped: they close over the S3 key selected for the session.
 */
public interface IamProvider {

    IamCapabilities capabilities();

    // ── Users ────────────────────────────────────────────────────────────

    List<IamUser> listUsers();

    /**
     * Creates a user and mints its first access key. Providers where a user cannot exist without
     * credentials (MinIO) need this to be one operation, so it is one operation everywhere.
     */
    IamAccessKey createUser(String userName);

    /** Removes the user along with anything that would otherwise block the delete. */
    void deleteUser(String userName);

    // ── Groups ───────────────────────────────────────────────────────────

    List<IamGroup> listGroups();

    void createGroup(String groupName);

    void deleteGroup(String groupName);

    List<String> listGroupMembers(String groupName);

    void addUserToGroup(String userName, String groupName);

    void removeUserFromGroup(String userName, String groupName);

    // ── Policies ─────────────────────────────────────────────────────────

    List<IamPolicySummary> listPolicies();

    String getPolicyDocument(String policyId);

    IamPolicySummary createPolicy(String name, String document);

    void updatePolicy(String policyId, String document);

    void deletePolicy(String policyId);

    List<IamPolicySummary> listAttachedPolicies(IamTarget target);

    void attachPolicy(IamTarget target, String policyId);

    void detachPolicy(IamTarget target, String policyId);

    // ── Inline policies ──────────────────────────────────────────────────
    // Documents embedded directly on a user or group. On providers without standalone policy
    // CRUD (Ceph RGW) this is the only way to express a fine-grained, per-bucket grant.

    List<String> listInlinePolicies(IamTarget target);

    String getInlinePolicy(IamTarget target, String policyName);

    void putInlinePolicy(IamTarget target, String policyName, String document);

    void deleteInlinePolicy(IamTarget target, String policyName);

    // ── Access keys ──────────────────────────────────────────────────────

    List<IamAccessKeySummary> listAccessKeys(String userName);

    IamAccessKey createAccessKey(String userName);

    void deleteAccessKey(String userName, String accessKeyId);
}
