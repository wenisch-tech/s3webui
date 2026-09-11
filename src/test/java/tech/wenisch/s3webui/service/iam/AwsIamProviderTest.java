package tech.wenisch.s3webui.service.iam;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.AccessKeyMetadata;
import software.amazon.awssdk.services.iam.model.AttachedPolicy;
import software.amazon.awssdk.services.iam.model.CreateAccessKeyRequest;
import software.amazon.awssdk.services.iam.model.CreateAccessKeyResponse;
import software.amazon.awssdk.services.iam.model.CreatePolicyVersionRequest;
import software.amazon.awssdk.services.iam.model.CreateUserRequest;
import software.amazon.awssdk.services.iam.model.CreateUserResponse;
import software.amazon.awssdk.services.iam.model.DeletePolicyVersionRequest;
import software.amazon.awssdk.services.iam.model.DeleteUserRequest;
import software.amazon.awssdk.services.iam.model.DetachUserPolicyRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyResponse;
import software.amazon.awssdk.services.iam.model.GetPolicyVersionRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyVersionResponse;
import software.amazon.awssdk.services.iam.model.Group;
import software.amazon.awssdk.services.iam.model.ListAccessKeysRequest;
import software.amazon.awssdk.services.iam.model.ListAccessKeysResponse;
import software.amazon.awssdk.services.iam.model.ListAttachedUserPoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListAttachedUserPoliciesResponse;
import software.amazon.awssdk.services.iam.model.ListGroupsForUserRequest;
import software.amazon.awssdk.services.iam.model.ListGroupsForUserResponse;
import software.amazon.awssdk.services.iam.model.ListPoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListPolicyVersionsRequest;
import software.amazon.awssdk.services.iam.model.ListPolicyVersionsResponse;
import software.amazon.awssdk.services.iam.model.PolicyScopeType;
import software.amazon.awssdk.services.iam.paginators.ListPoliciesIterable;
import software.amazon.awssdk.services.iam.model.ListUserPoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListUserPoliciesResponse;
import software.amazon.awssdk.services.iam.model.Policy;
import software.amazon.awssdk.services.iam.model.PolicyVersion;
import software.amazon.awssdk.services.iam.model.RemoveUserFromGroupRequest;
import tech.wenisch.s3webui.model.iam.IamAccessKey;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.iam.model.GetUserPolicyResponse;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.ListGroupPoliciesResponse;
import software.amazon.awssdk.services.iam.paginators.ListGroupsIterable;
import software.amazon.awssdk.services.iam.paginators.ListUsersIterable;
import tech.wenisch.s3webui.model.iam.IamPolicySummary;
import tech.wenisch.s3webui.model.iam.IamTarget;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AwsIamProviderTest {

    private final IamClient iamClient = mock(IamClient.class);
    private final AwsIamProvider provider = new AwsIamProvider(iamClient);

    @Test
    void creatingAUserAlsoMintsItsFirstAccessKey() {
        when(iamClient.createUser(any(Consumer.class))).thenReturn(CreateUserResponse.builder().build());
        when(iamClient.createAccessKey(any(Consumer.class))).thenReturn(CreateAccessKeyResponse.builder()
                .accessKey(AccessKey.builder().accessKeyId("AKIA1").secretAccessKey("s3cr3t").build())
                .build());

        IamAccessKey key = provider.createUser("alice");

        assertEquals("alice", key.userName());
        assertEquals("AKIA1", key.accessKeyId());
        assertEquals("s3cr3t", key.secretAccessKey());

        var order = inOrder(iamClient);
        order.verify(iamClient).createUser(any(Consumer.class));
        order.verify(iamClient).createAccessKey(any(Consumer.class));
    }

    @Test
    void deletingAUserClearsWhatWouldBlockTheDelete() {
        when(iamClient.listAttachedUserPolicies(any(Consumer.class)))
                .thenReturn(ListAttachedUserPoliciesResponse.builder()
                        .attachedPolicies(AttachedPolicy.builder()
                                .policyName("ReadOnly").policyArn("arn:aws:iam::1:policy/ReadOnly").build())
                        .build());
        when(iamClient.listUserPolicies(any(Consumer.class)))
                .thenReturn(ListUserPoliciesResponse.builder().policyNames("inline-1").build());
        when(iamClient.listGroupsForUser(any(Consumer.class)))
                .thenReturn(ListGroupsForUserResponse.builder()
                        .groups(Group.builder().groupName("devs").build()).build());
        when(iamClient.listAccessKeys(any(Consumer.class)))
                .thenReturn(ListAccessKeysResponse.builder()
                        .accessKeyMetadata(AccessKeyMetadata.builder().accessKeyId("AKIA1").build()).build());

        provider.deleteUser("alice");

        var order = inOrder(iamClient);
        order.verify(iamClient).detachUserPolicy(any(Consumer.class));
        order.verify(iamClient).deleteUserPolicy(any(Consumer.class));
        order.verify(iamClient).removeUserFromGroup(any(Consumer.class));
        order.verify(iamClient).deleteAccessKey(any(Consumer.class));
        order.verify(iamClient).deleteUser(any(Consumer.class));
    }

    @Test
    void updatingAPolicyAddsANewDefaultVersion() {
        when(iamClient.listPolicyVersions(any(Consumer.class)))
                .thenReturn(ListPolicyVersionsResponse.builder()
                        .versions(version("v1", true, Instant.parse("2026-01-01T00:00:00Z")))
                        .build());

        provider.updatePolicy("arn:aws:iam::1:policy/Custom", "{\"Version\":\"2012-10-17\"}");

        verify(iamClient, never()).deletePolicyVersion(any(Consumer.class));
        verify(iamClient).createPolicyVersion(any(Consumer.class));
    }

    @Test
    void updatingAPolicyAtTheVersionCapPrunesTheOldestNonDefaultVersion() {
        when(iamClient.listPolicyVersions(any(Consumer.class)))
                .thenReturn(ListPolicyVersionsResponse.builder()
                        .versions(
                                version("v5", true, Instant.parse("2026-05-01T00:00:00Z")),
                                version("v3", false, Instant.parse("2026-03-01T00:00:00Z")),
                                version("v1", false, Instant.parse("2026-01-01T00:00:00Z")),
                                version("v2", false, Instant.parse("2026-02-01T00:00:00Z")),
                                version("v4", false, Instant.parse("2026-04-01T00:00:00Z")))
                        .build());

        provider.updatePolicy("arn:aws:iam::1:policy/Custom", "{}");

        ArgumentCaptor<Consumer<DeletePolicyVersionRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(iamClient).deletePolicyVersion(captor.capture());
        var request = DeletePolicyVersionRequest.builder();
        captor.getValue().accept(request);
        assertEquals("v1", request.build().versionId(), "the oldest non-default version should go");

        verify(iamClient).createPolicyVersion(any(Consumer.class));
    }

    @Test
    void awsManagedPoliciesAreReportedAsNotEditableAndRefuseChanges() {
        String awsManaged = "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess";

        assertThrows(IllegalArgumentException.class, () -> provider.updatePolicy(awsManaged, "{}"));
        assertThrows(IllegalArgumentException.class, () -> provider.deletePolicy(awsManaged));
        verify(iamClient, never()).createPolicyVersion(any(Consumer.class));
        verify(iamClient, never()).deletePolicy(any(Consumer.class));
    }

    @Test
    void policyDocumentsAreUrlDecoded() {
        when(iamClient.getPolicy(any(Consumer.class))).thenReturn(GetPolicyResponse.builder()
                .policy(Policy.builder().defaultVersionId("v2").build()).build());
        when(iamClient.getPolicyVersion(any(Consumer.class))).thenReturn(GetPolicyVersionResponse.builder()
                .policyVersion(PolicyVersion.builder()
                        .document("%7B%22Version%22%3A%222012-10-17%22%7D").build())
                .build());

        assertEquals("{\"Version\":\"2012-10-17\"}",
                provider.getPolicyDocument("arn:aws:iam::1:policy/Custom"));
    }

    @Test
    void policyListingIsScopedToCustomerManagedPolicies() {
        stubUsers();
        stubGroups();
        stubPolicies();

        provider.listPolicies();

        ArgumentCaptor<Consumer<ListPoliciesRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(iamClient, atLeastOnce()).listPoliciesPaginator(captor.capture());
        var request = ListPoliciesRequest.builder();
        captor.getValue().accept(request);
        assertEquals(PolicyScopeType.LOCAL, request.build().scope(),
                "AWS ships 1000+ managed policies; listing them would bury the curated ones");
    }

    // Capability probing

    @Test
    void everythingIsReportedWhenEveryProbeSucceeds() {
        stubUsers();
        stubGroups();
        stubPolicies();

        var capabilities = provider.capabilities();

        assertTrue(capabilities.available());
        assertTrue(capabilities.groups());
        assertTrue(capabilities.accessKeys());
        assertTrue(capabilities.managedPolicies());
        assertTrue(capabilities.inlinePolicies());
    }

    @Test
    void aProviderWithoutStandalonePoliciesStaysAvailableWithThatOneFeatureOff() {
        stubUsers();
        stubGroups();
        when(iamClient.listPoliciesPaginator(any(Consumer.class))).thenThrow(awsError("NotImplemented", 501));

        var capabilities = provider.capabilities();

        assertTrue(capabilities.available(), "Ceph answers users and groups; only policies are missing");
        assertTrue(capabilities.groups());
        assertFalse(capabilities.managedPolicies());
        assertTrue(capabilities.inlinePolicies());
    }

    @Test
    void withoutStandalonePoliciesTheBuiltInCatalogueIsListedInstead() {
        stubUsers();
        stubGroups();
        when(iamClient.listPoliciesPaginator(any(Consumer.class))).thenThrow(awsError("NotImplemented", 501));

        var policies = provider.listPolicies();

        assertEquals(6, policies.size());
        assertTrue(policies.stream().noneMatch(IamPolicySummary::editable));
        assertTrue(policies.stream().anyMatch(policy -> "AmazonS3ReadOnlyAccess".equals(policy.name())));
        assertTrue(policies.stream().allMatch(policy -> policy.id().startsWith("arn:aws:iam::aws:policy/")));
    }

    @Test
    void anUnauthorisedKeyIsReportedWithTheCephAccountRootHint() {
        when(iamClient.listUsersPaginator()).thenThrow(awsError("AccessDenied", 403));

        var capabilities = provider.capabilities();

        assertFalse(capabilities.available());
        assertTrue(capabilities.reason().contains("account root"),
                "expected the Ceph hint, got: " + capabilities.reason());
    }

    @Test
    void theProbeRunsOnlyOncePerProvider() {
        stubUsers();
        stubGroups();
        stubPolicies();

        provider.capabilities();
        provider.capabilities();

        verify(iamClient, times(1)).listUsersPaginator();
    }

    // Inline policies

    @Test
    void inlineUserPolicyDocumentsAreUrlDecoded() {
        when(iamClient.getUserPolicy(any(Consumer.class))).thenReturn(GetUserPolicyResponse.builder()
                .policyDocument("%7B%22Version%22%3A%222012-10-17%22%7D").build());

        assertEquals("{\"Version\":\"2012-10-17\"}",
                provider.getInlinePolicy(IamTarget.user("alice"), "read-one-bucket"));
    }

    @Test
    void inlinePolicyWritesGoToTheUserOrTheGroupAsAppropriate() {
        provider.putInlinePolicy(IamTarget.user("alice"), "p", "{}");
        provider.putInlinePolicy(IamTarget.group("devs"), "p", "{}");
        provider.deleteInlinePolicy(IamTarget.user("alice"), "p");
        provider.deleteInlinePolicy(IamTarget.group("devs"), "p");

        verify(iamClient).putUserPolicy(any(Consumer.class));
        verify(iamClient).putGroupPolicy(any(Consumer.class));
        verify(iamClient).deleteUserPolicy(any(Consumer.class));
        verify(iamClient).deleteGroupPolicy(any(Consumer.class));
    }

    @Test
    void inlinePolicyNamesComeFromTheRightListCall() {
        when(iamClient.listUserPolicies(any(Consumer.class)))
                .thenReturn(ListUserPoliciesResponse.builder().policyNames("on-user").build());
        when(iamClient.listGroupPolicies(any(Consumer.class)))
                .thenReturn(ListGroupPoliciesResponse.builder().policyNames("on-group").build());

        assertEquals(List.of("on-user"), provider.listInlinePolicies(IamTarget.user("alice")));
        assertEquals(List.of("on-group"), provider.listInlinePolicies(IamTarget.group("devs")));
    }

    // Helpers

    private void stubUsers() {
        ListUsersIterable paginator = mock(ListUsersIterable.class);
        SdkIterable<software.amazon.awssdk.services.iam.model.User> none = Collections::emptyIterator;
        when(paginator.users()).thenReturn(none);
        when(iamClient.listUsersPaginator()).thenReturn(paginator);
    }

    private void stubGroups() {
        ListGroupsIterable paginator = mock(ListGroupsIterable.class);
        SdkIterable<Group> none = Collections::emptyIterator;
        when(paginator.groups()).thenReturn(none);
        when(iamClient.listGroupsPaginator()).thenReturn(paginator);
    }

    private void stubPolicies() {
        ListPoliciesIterable paginator = mock(ListPoliciesIterable.class);
        SdkIterable<Policy> none = Collections::emptyIterator;
        when(paginator.policies()).thenReturn(none);
        when(iamClient.listPoliciesPaginator(any(Consumer.class))).thenReturn(paginator);
    }

    private static IamException awsError(String errorCode, int statusCode) {
        return (IamException) IamException.builder()
                .statusCode(statusCode)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode(errorCode).errorMessage(errorCode).build())
                .build();
    }

    private static PolicyVersion version(String id, boolean isDefault, Instant createdAt) {
        return PolicyVersion.builder().versionId(id).isDefaultVersion(isDefault).createDate(createdAt).build();
    }
}
