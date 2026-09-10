package tech.wenisch.s3webui.service.iam;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachedPolicy;
import software.amazon.awssdk.services.iam.model.PolicyScopeType;
import software.amazon.awssdk.services.iam.model.PolicyVersion;
import tech.wenisch.s3webui.model.iam.IamAccessKey;
import tech.wenisch.s3webui.model.iam.IamAccessKeySummary;
import tech.wenisch.s3webui.model.iam.IamCapabilities;
import tech.wenisch.s3webui.model.iam.IamGroup;
import tech.wenisch.s3webui.model.iam.IamPolicySummary;
import tech.wenisch.s3webui.model.iam.IamTarget;
import tech.wenisch.s3webui.model.iam.IamUser;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/** Talks the real AWS IAM API. Also works against IAM-compatible endpoints such as LocalStack. */
@RequiredArgsConstructor
public class AwsIamProvider implements IamProvider {

    /** AWS keeps at most five versions of a managed policy; the sixth create fails. */
    private static final int MAX_POLICY_VERSIONS = 5;

    private static final String AWS_MANAGED_PREFIX = "arn:aws:iam::aws:policy/";

    private final IamClient iamClient;

    @Override
    public IamCapabilities capabilities() {
        return new IamCapabilities(true, "AWS IAM", null, true, true);
    }

    // ── Users ────────────────────────────────────────────────────────────

    @Override
    public List<IamUser> listUsers() {
        return iamClient.listUsersPaginator().users().stream()
                .map(user -> new IamUser(user.userName(), user.arn(), user.createDate()))
                .toList();
    }

    @Override
    public IamAccessKey createUser(String userName) {
        iamClient.createUser(request -> request.userName(userName));
        return createAccessKey(userName);
    }

    @Override
    public void deleteUser(String userName) {
        // AWS refuses to delete a user that still has anything hanging off it.
        for (AttachedPolicy policy : iamClient.listAttachedUserPolicies(r -> r.userName(userName)).attachedPolicies()) {
            iamClient.detachUserPolicy(r -> r.userName(userName).policyArn(policy.policyArn()));
        }
        for (String policyName : iamClient.listUserPolicies(r -> r.userName(userName)).policyNames()) {
            iamClient.deleteUserPolicy(r -> r.userName(userName).policyName(policyName));
        }
        iamClient.listGroupsForUser(r -> r.userName(userName)).groups()
                .forEach(group -> iamClient.removeUserFromGroup(
                        r -> r.userName(userName).groupName(group.groupName())));
        iamClient.listAccessKeys(r -> r.userName(userName)).accessKeyMetadata()
                .forEach(key -> iamClient.deleteAccessKey(
                        r -> r.userName(userName).accessKeyId(key.accessKeyId())));

        iamClient.deleteUser(request -> request.userName(userName));
    }

    // ── Groups ───────────────────────────────────────────────────────────

    @Override
    public List<IamGroup> listGroups() {
        return iamClient.listGroupsPaginator().groups().stream()
                .map(group -> new IamGroup(group.groupName(), group.arn(), group.createDate()))
                .toList();
    }

    @Override
    public void createGroup(String groupName) {
        iamClient.createGroup(request -> request.groupName(groupName));
    }

    @Override
    public void deleteGroup(String groupName) {
        for (AttachedPolicy policy : iamClient.listAttachedGroupPolicies(r -> r.groupName(groupName)).attachedPolicies()) {
            iamClient.detachGroupPolicy(r -> r.groupName(groupName).policyArn(policy.policyArn()));
        }
        for (String policyName : iamClient.listGroupPolicies(r -> r.groupName(groupName)).policyNames()) {
            iamClient.deleteGroupPolicy(r -> r.groupName(groupName).policyName(policyName));
        }
        iamClient.getGroup(r -> r.groupName(groupName)).users()
                .forEach(user -> iamClient.removeUserFromGroup(
                        r -> r.groupName(groupName).userName(user.userName())));

        iamClient.deleteGroup(request -> request.groupName(groupName));
    }

    @Override
    public List<String> listGroupMembers(String groupName) {
        return iamClient.getGroup(request -> request.groupName(groupName)).users().stream()
                .map(software.amazon.awssdk.services.iam.model.User::userName)
                .toList();
    }

    @Override
    public void addUserToGroup(String userName, String groupName) {
        iamClient.addUserToGroup(request -> request.userName(userName).groupName(groupName));
    }

    @Override
    public void removeUserFromGroup(String userName, String groupName) {
        iamClient.removeUserFromGroup(request -> request.userName(userName).groupName(groupName));
    }

    // ── Policies ─────────────────────────────────────────────────────────

    /**
     * Customer-managed policies only. AWS ships over a thousand managed policies, which would bury
     * the handful an operator actually curates here and make the attach picker unusable.
     */
    @Override
    public List<IamPolicySummary> listPolicies() {
        return iamClient.listPoliciesPaginator(request -> request.scope(PolicyScopeType.LOCAL)).policies().stream()
                .map(policy -> new IamPolicySummary(
                        policy.arn(), policy.policyName(), policy.arn(), isEditable(policy.arn())))
                .toList();
    }

    @Override
    public String getPolicyDocument(String policyId) {
        String defaultVersionId = iamClient.getPolicy(request -> request.policyArn(policyId))
                .policy().defaultVersionId();
        String document = iamClient.getPolicyVersion(
                        request -> request.policyArn(policyId).versionId(defaultVersionId))
                .policyVersion().document();
        // IAM returns the document URL-encoded.
        return URLDecoder.decode(document, StandardCharsets.UTF_8);
    }

    @Override
    public IamPolicySummary createPolicy(String name, String document) {
        var policy = iamClient.createPolicy(
                request -> request.policyName(name).policyDocument(document)).policy();
        return new IamPolicySummary(policy.arn(), policy.policyName(), policy.arn(), true);
    }

    @Override
    public void updatePolicy(String policyId, String document) {
        requireEditable(policyId);
        pruneOldestVersionIfAtCap(policyId);
        iamClient.createPolicyVersion(
                request -> request.policyArn(policyId).policyDocument(document).setAsDefault(true));
    }

    @Override
    public void deletePolicy(String policyId) {
        requireEditable(policyId);
        // Non-default versions and attachments both block the delete.
        iamClient.listPolicyVersions(r -> r.policyArn(policyId)).versions().stream()
                .filter(version -> !Boolean.TRUE.equals(version.isDefaultVersion()))
                .forEach(version -> iamClient.deletePolicyVersion(
                        r -> r.policyArn(policyId).versionId(version.versionId())));

        var entities = iamClient.listEntitiesForPolicy(r -> r.policyArn(policyId));
        entities.policyUsers().forEach(user -> iamClient.detachUserPolicy(
                r -> r.userName(user.userName()).policyArn(policyId)));
        entities.policyGroups().forEach(group -> iamClient.detachGroupPolicy(
                r -> r.groupName(group.groupName()).policyArn(policyId)));
        entities.policyRoles().forEach(role -> iamClient.detachRolePolicy(
                r -> r.roleName(role.roleName()).policyArn(policyId)));

        iamClient.deletePolicy(request -> request.policyArn(policyId));
    }

    @Override
    public List<IamPolicySummary> listAttachedPolicies(IamTarget target) {
        List<AttachedPolicy> attached = target.type() == IamTarget.Type.USER
                ? iamClient.listAttachedUserPolicies(r -> r.userName(target.name())).attachedPolicies()
                : iamClient.listAttachedGroupPolicies(r -> r.groupName(target.name())).attachedPolicies();
        return attached.stream()
                .map(policy -> new IamPolicySummary(
                        policy.policyArn(), policy.policyName(), policy.policyArn(),
                        isEditable(policy.policyArn())))
                .toList();
    }

    @Override
    public void attachPolicy(IamTarget target, String policyId) {
        if (target.type() == IamTarget.Type.USER) {
            iamClient.attachUserPolicy(request -> request.userName(target.name()).policyArn(policyId));
        } else {
            iamClient.attachGroupPolicy(request -> request.groupName(target.name()).policyArn(policyId));
        }
    }

    @Override
    public void detachPolicy(IamTarget target, String policyId) {
        if (target.type() == IamTarget.Type.USER) {
            iamClient.detachUserPolicy(request -> request.userName(target.name()).policyArn(policyId));
        } else {
            iamClient.detachGroupPolicy(request -> request.groupName(target.name()).policyArn(policyId));
        }
    }

    // ── Access keys ──────────────────────────────────────────────────────

    @Override
    public List<IamAccessKeySummary> listAccessKeys(String userName) {
        return iamClient.listAccessKeys(request -> request.userName(userName)).accessKeyMetadata().stream()
                .map(key -> new IamAccessKeySummary(
                        key.accessKeyId(), key.statusAsString(), key.createDate()))
                .toList();
    }

    @Override
    public IamAccessKey createAccessKey(String userName) {
        var key = iamClient.createAccessKey(request -> request.userName(userName)).accessKey();
        return new IamAccessKey(userName, key.accessKeyId(), key.secretAccessKey());
    }

    @Override
    public void deleteAccessKey(String userName, String accessKeyId) {
        iamClient.deleteAccessKey(request -> request.userName(userName).accessKeyId(accessKeyId));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static boolean isEditable(String arn) {
        return arn == null || !arn.startsWith(AWS_MANAGED_PREFIX);
    }

    private static void requireEditable(String policyId) {
        if (!isEditable(policyId)) {
            throw new IllegalArgumentException(
                    "AWS-managed policies cannot be modified or deleted, only attached");
        }
    }

    /** Frees a version slot so {@code CreatePolicyVersion} does not fail with LimitExceeded. */
    private void pruneOldestVersionIfAtCap(String policyArn) {
        List<PolicyVersion> versions = iamClient.listPolicyVersions(r -> r.policyArn(policyArn)).versions();
        if (versions.size() < MAX_POLICY_VERSIONS) {
            return;
        }
        versions.stream()
                .filter(version -> !Boolean.TRUE.equals(version.isDefaultVersion()))
                .min(Comparator.comparing(PolicyVersion::createDate))
                .ifPresent(oldest -> iamClient.deletePolicyVersion(
                        r -> r.policyArn(policyArn).versionId(oldest.versionId())));
    }
}
