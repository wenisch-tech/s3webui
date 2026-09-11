package tech.wenisch.s3webui.service.iam;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
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

import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Talks the IAM API. Serves real AWS, Ceph RGW (Squid and later) and any other IAM-compatible
 * endpoint such as LocalStack; {@link #capabilities()} probes which parts actually answer.
 */
@Slf4j
@RequiredArgsConstructor
public class AwsIamProvider implements IamProvider {

    /** AWS keeps at most five versions of a managed policy; the sixth create fails. */
    private static final int MAX_POLICY_VERSIONS = 5;

    private static final String AWS_MANAGED_PREFIX = "arn:aws:iam::aws:policy/";

    /**
     * What Ceph RGW offers when standalone policies are unsupported: a fixed catalogue that can
     * be attached but not inspected or edited. Listing these keeps the attach picker useful.
     */
    private static final List<IamPolicySummary> BUILT_IN_POLICIES = Stream.of(
                    "AmazonS3FullAccess",
                    "AmazonS3ReadOnlyAccess",
                    "IAMFullAccess",
                    "IAMReadOnlyAccess",
                    "AmazonSNSFullAccess",
                    "AmazonSNSReadOnlyAccess")
            .map(name -> new IamPolicySummary(AWS_MANAGED_PREFIX + name, name, AWS_MANAGED_PREFIX + name, false))
            .toList();

    /** Error codes a provider uses to say "I do not implement this operation". */
    private static final Set<String> UNSUPPORTED_CODES =
            Set.of("NotImplemented", "InvalidAction", "MethodNotAllowed", "InvalidRequest");

    private final IamClient iamClient;

    /** Probed once per request; the provider bean is request-scoped. */
    private IamCapabilities cachedCapabilities;

    @Override
    public IamCapabilities capabilities() {
        if (cachedCapabilities == null) {
            cachedCapabilities = probeCapabilities();
        }
        return cachedCapabilities;
    }

    private IamCapabilities probeCapabilities() {
        try {
            listUsers();
        } catch (RuntimeException ex) {
            // A refusal here is about the endpoint or the key, not about one missing operation.
            return IamCapabilities.unavailable(unavailableReason(ex));
        }
        boolean groups = supports(this::listGroups);
        boolean managedPolicies = supports(() -> iamClient.listPoliciesPaginator(
                request -> request.scope(PolicyScopeType.LOCAL)).policies().iterator().hasNext());
        // Every IAM implementation we target supports PutUserPolicy, and there is no cheap probe
        // for it without an existing user to hang one off.
        return new IamCapabilities(true, "IAM API", null, groups, true, managedPolicies, true);
    }

    private static String unavailableReason(RuntimeException ex) {
        if (ex instanceof AwsServiceException awsEx
                && awsEx.awsErrorDetails() != null
                && "AccessDenied".equals(awsEx.awsErrorDetails().errorCode())) {
            return "The selected S3 key is not authorised for the IAM API. On Ceph the IAM API is "
                    + "restricted to an account root user's key.";
        }
        if (isTimeout(ex)) {
            // A backend without an IAM API tends to leave the request hanging rather than
            // refusing it outright, so a timeout here is the normal "not supported" signal.
            return "This S3 provider did not answer an IAM request in time. It most likely does "
                    + "not implement the IAM API - MinIO, for instance, uses its own admin API.";
        }
        return "This S3 provider did not answer an IAM request: " + rootMessage(ex);
    }

    private static boolean isTimeout(Throwable throwable) {
        for (Throwable cause = throwable; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof ApiCallTimeoutException
                    || cause instanceof ApiCallAttemptTimeoutException
                    || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** True when the call works, false when the provider says it does not implement it. */
    private static boolean supports(Runnable call) {
        try {
            call.run();
            return true;
        } catch (AwsServiceException ex) {
            if (isUnsupported(ex)) {
                return false;
            }
            log.debug("IAM capability probe failed for a reason other than lack of support", ex);
            return false;
        } catch (RuntimeException ex) {
            log.debug("IAM capability probe failed", ex);
            return false;
        }
    }

    private static boolean isUnsupported(AwsServiceException ex) {
        if (ex.statusCode() == 404 || ex.statusCode() == 405 || ex.statusCode() == 501) {
            return true;
        }
        return ex.awsErrorDetails() != null && UNSUPPORTED_CODES.contains(ex.awsErrorDetails().errorCode());
    }

    private static String rootMessage(Throwable throwable) {
        if (throwable instanceof AwsServiceException awsEx
                && awsEx.awsErrorDetails() != null
                && awsEx.awsErrorDetails().errorMessage() != null
                && !awsEx.awsErrorDetails().errorMessage().isBlank()) {
            return awsEx.awsErrorDetails().errorMessage();
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
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
     * the handful an operator actually curates here and make the attach picker unusable. Providers
     * without standalone policies fall back to their fixed attachable catalogue.
     */
    @Override
    public List<IamPolicySummary> listPolicies() {
        if (!capabilities().managedPolicies()) {
            return BUILT_IN_POLICIES;
        }
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

    // ── Inline policies ──────────────────────────────────────────────────

    @Override
    public List<String> listInlinePolicies(IamTarget target) {
        return target.type() == IamTarget.Type.USER
                ? iamClient.listUserPolicies(r -> r.userName(target.name())).policyNames()
                : iamClient.listGroupPolicies(r -> r.groupName(target.name())).policyNames();
    }

    @Override
    public String getInlinePolicy(IamTarget target, String policyName) {
        String document = target.type() == IamTarget.Type.USER
                ? iamClient.getUserPolicy(r -> r.userName(target.name()).policyName(policyName)).policyDocument()
                : iamClient.getGroupPolicy(r -> r.groupName(target.name()).policyName(policyName)).policyDocument();
        return URLDecoder.decode(document, StandardCharsets.UTF_8);
    }

    @Override
    public void putInlinePolicy(IamTarget target, String policyName, String document) {
        if (target.type() == IamTarget.Type.USER) {
            iamClient.putUserPolicy(r -> r.userName(target.name())
                    .policyName(policyName).policyDocument(document));
        } else {
            iamClient.putGroupPolicy(r -> r.groupName(target.name())
                    .policyName(policyName).policyDocument(document));
        }
    }

    @Override
    public void deleteInlinePolicy(IamTarget target, String policyName) {
        if (target.type() == IamTarget.Type.USER) {
            iamClient.deleteUserPolicy(r -> r.userName(target.name()).policyName(policyName));
        } else {
            iamClient.deleteGroupPolicy(r -> r.groupName(target.name()).policyName(policyName));
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
