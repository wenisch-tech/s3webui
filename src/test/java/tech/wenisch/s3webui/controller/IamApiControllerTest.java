package tech.wenisch.s3webui.controller;

import org.junit.jupiter.api.Test;
import tech.wenisch.s3webui.model.iam.IamTarget;
import tech.wenisch.s3webui.service.AuditHistoryService;
import tech.wenisch.s3webui.service.IamService;
import tech.wenisch.s3webui.service.iam.IamProvider;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamApiControllerTest {

    private final IamService iamService = mock(IamService.class);
    private final IamProvider provider = mock(IamProvider.class);
    private final AuditHistoryService audit = mock(AuditHistoryService.class);
    private final IamApiController controller =
            new IamApiController(iamService, new AuditSupport(audit), JsonMapper.builder().build());

    private void withProvider() {
        when(iamService.provider()).thenReturn(provider);
    }

    @Test
    void creatingAUserIsAuditedAgainstTheUserName() {
        when(iamService.createUserAndStoreKey(eq("alice"), isNull()))
                .thenReturn(new IamService.StoredKey("alice", "AKIA1", 1L, "IAM: alice"));

        controller.createUser(new IamApiController.NameRequest("alice"), null);

        verify(audit).record(isNull(), eq("CREATE"), eq("IAM_USER"), isNull(), eq("alice"),
                eq("Created IAM user and stored its access key"));
    }

    @Test
    void aFailedCreateIsAuditedAndRethrown() {
        when(iamService.createUserAndStoreKey(any(), any()))
                .thenThrow(new RuntimeException("EntityAlreadyExists"));

        assertThrows(RuntimeException.class,
                () -> controller.createUser(new IamApiController.NameRequest("alice"), null));

        verify(audit).record(isNull(), eq("CREATE"), eq("IAM_USER"), isNull(), eq("alice"),
                startsWith("Failed: "));
    }

    @Test
    void aBlankUserNameIsRejectedBeforeTheProviderIsTouched() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.createUser(new IamApiController.NameRequest("  "), null));

        verify(iamService, never()).createUserAndStoreKey(any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aMalformedPolicyDocumentIsRejectedBeforeTheProviderIsTouched() {
        assertThrows(IllegalArgumentException.class, () -> controller.createPolicy(
                new IamApiController.PolicyRequest("Broken", "{ not json"), null));
        assertThrows(IllegalArgumentException.class,
                () -> controller.updatePolicy("arn:aws:iam::1:policy/X", "{ not json", null));

        verify(iamService, never()).provider();
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void deletingAUserIsAudited() {
        withProvider();

        controller.deleteUser("alice", null);

        verify(provider).deleteUser("alice");
        verify(audit).record(isNull(), eq("DELETE"), eq("IAM_USER"), isNull(), eq("alice"),
                eq("Deleted IAM user"));
    }

    @Test
    void groupMembershipChangesAreAuditedAgainstTheGroup() {
        withProvider();

        controller.addGroupMember("devs", "alice", null);
        controller.removeGroupMember("devs", "alice", null);

        verify(provider).addUserToGroup("alice", "devs");
        verify(provider).removeUserFromGroup("alice", "devs");
        verify(audit).record(isNull(), eq("EDIT"), eq("IAM_GROUP"), isNull(), eq("devs"),
                eq("Added alice to the group"));
        verify(audit).record(isNull(), eq("EDIT"), eq("IAM_GROUP"), isNull(), eq("devs"),
                eq("Removed alice from the group"));
    }

    @Test
    void attachingAPolicyToAUserTargetsThatUser() {
        withProvider();

        controller.attachUserPolicy("alice",
                new IamApiController.PolicyRefRequest("arn:aws:iam::1:policy/ReadOnly"), null);

        verify(provider).attachPolicy(IamTarget.user("alice"), "arn:aws:iam::1:policy/ReadOnly");
        verify(audit).record(isNull(), eq("EDIT"), eq("IAM_USER"), isNull(), eq("alice"),
                eq("Attached policy arn:aws:iam::1:policy/ReadOnly"));
    }

    @Test
    void detachingAPolicyFromAGroupTargetsThatGroup() {
        withProvider();

        controller.detachGroupPolicy("devs", "arn:aws:iam::1:policy/ReadOnly", null);

        verify(provider).detachPolicy(IamTarget.group("devs"), "arn:aws:iam::1:policy/ReadOnly");
        verify(audit).record(isNull(), eq("EDIT"), eq("IAM_GROUP"), isNull(), eq("devs"),
                startsWith("Detached policy "));
    }

    @Test
    void deletingAnAccessKeyNamesTheKeyInTheAuditDetail() {
        withProvider();

        controller.deleteAccessKey("alice", "AKIA1", null);

        verify(provider).deleteAccessKey("alice", "AKIA1");
        verify(audit).record(isNull(), eq("DELETE"), eq("IAM_ACCESS_KEY"), isNull(), eq("alice"),
                eq("Deleted access key AKIA1"));
    }

    @Test
    void settingAnInlinePolicyIsAuditedAgainstTheIdentity() {
        withProvider();

        controller.putUserInlinePolicy("alice", "read-one-bucket", "{\"Version\":\"2012-10-17\"}", null);
        controller.putGroupInlinePolicy("devs", "read-one-bucket", "{}", null);

        verify(provider).putInlinePolicy(IamTarget.user("alice"), "read-one-bucket", "{\"Version\":\"2012-10-17\"}");
        verify(provider).putInlinePolicy(IamTarget.group("devs"), "read-one-bucket", "{}");
        verify(audit).record(isNull(), eq("EDIT"), eq("IAM_USER"), isNull(), eq("alice"),
                eq("Set inline policy read-one-bucket"));
        verify(audit).record(isNull(), eq("EDIT"), eq("IAM_GROUP"), isNull(), eq("devs"),
                eq("Set inline policy read-one-bucket"));
    }

    @Test
    void removingAnInlinePolicyIsAudited() {
        withProvider();

        controller.deleteUserInlinePolicy("alice", "read-one-bucket", null);

        verify(provider).deleteInlinePolicy(IamTarget.user("alice"), "read-one-bucket");
        verify(audit).record(isNull(), eq("EDIT"), eq("IAM_USER"), isNull(), eq("alice"),
                eq("Removed inline policy read-one-bucket"));
    }

    @Test
    void aMalformedInlinePolicyIsRejectedBeforeTheProviderIsTouched() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.putUserInlinePolicy("alice", "broken", "{ not json", null));

        verify(iamService, never()).provider();
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aFailedProviderCallStillRecordsTheFailure() {
        withProvider();
        doThrow(new RuntimeException("NoSuchEntity")).when(provider).deleteGroup("ghosts");

        assertThrows(RuntimeException.class, () -> controller.deleteGroup("ghosts", null));

        verify(audit).record(isNull(), eq("DELETE"), eq("IAM_GROUP"), isNull(), eq("ghosts"),
                eq("Failed: NoSuchEntity"));
    }
}
