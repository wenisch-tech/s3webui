package tech.wenisch.s3webui.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.s3webui.model.iam.IamAccessKeySummary;
import tech.wenisch.s3webui.model.iam.IamCapabilities;
import tech.wenisch.s3webui.model.iam.IamGroup;
import tech.wenisch.s3webui.model.iam.IamPolicySummary;
import tech.wenisch.s3webui.model.iam.IamTarget;
import tech.wenisch.s3webui.model.iam.IamUser;
import tech.wenisch.s3webui.service.IamService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IAM management for the storage backend. Locked to {@code ROLE_ADMIN} by {@code SecurityConfig},
 * which matches {@code /api/admin/**}.
 *
 * <p>Every mutation is audited. Resource names go into the audit log's object-key column; the
 * bucket column stays null because none of these resources belong to a bucket.
 */
@RestController
@RequestMapping("/api/admin/iam")
@RequiredArgsConstructor
public class IamApiController {

    private static final String USER = "IAM_USER";
    private static final String GROUP = "IAM_GROUP";
    private static final String POLICY = "IAM_POLICY";
    private static final String ACCESS_KEY = "IAM_ACCESS_KEY";

    private final IamService iamService;
    private final AuditSupport audit;
    private final JsonMapper jsonMapper;

    @GetMapping("/capabilities")
    public IamCapabilities capabilities() {
        return iamService.capabilities();
    }

    // ── Users ────────────────────────────────────────────────────────────

    @GetMapping("/users")
    public List<IamUser> listUsers() {
        return iamService.provider().listUsers();
    }

    @PostMapping("/users")
    public IamService.StoredKey createUser(@RequestBody NameRequest request, Principal principal) {
        String userName = requireName(request, "User name");
        return audit.auditedCall(principal, "CREATE", USER, userName,
                "Created IAM user and stored its access key",
                () -> iamService.createUserAndStoreKey(userName, username(principal)));
    }

    @DeleteMapping("/users/{userName}")
    public void deleteUser(@PathVariable String userName, Principal principal) {
        audit.audited(principal, "DELETE", USER, userName, "Deleted IAM user",
                () -> iamService.provider().deleteUser(userName));
    }

    // ── Access keys ──────────────────────────────────────────────────────

    @GetMapping("/users/{userName}/keys")
    public List<IamAccessKeySummary> listAccessKeys(@PathVariable String userName) {
        return iamService.provider().listAccessKeys(userName);
    }

    @PostMapping("/users/{userName}/keys")
    public IamService.StoredKey createAccessKey(@PathVariable String userName, Principal principal) {
        return audit.auditedCall(principal, "CREATE", ACCESS_KEY, userName,
                "Created an access key and stored it",
                () -> iamService.createAccessKeyAndStore(userName, username(principal)));
    }

    @DeleteMapping("/users/{userName}/keys/{accessKeyId}")
    public void deleteAccessKey(@PathVariable String userName, @PathVariable String accessKeyId,
                                Principal principal) {
        audit.audited(principal, "DELETE", ACCESS_KEY, userName,
                "Deleted access key " + accessKeyId,
                () -> iamService.provider().deleteAccessKey(userName, accessKeyId));
    }

    // ── Groups ───────────────────────────────────────────────────────────

    @GetMapping("/groups")
    public List<IamGroup> listGroups() {
        return iamService.provider().listGroups();
    }

    @PostMapping("/groups")
    public void createGroup(@RequestBody NameRequest request, Principal principal) {
        String groupName = requireName(request, "Group name");
        audit.audited(principal, "CREATE", GROUP, groupName, "Created IAM group",
                () -> iamService.provider().createGroup(groupName));
    }

    @DeleteMapping("/groups/{groupName}")
    public void deleteGroup(@PathVariable String groupName, Principal principal) {
        audit.audited(principal, "DELETE", GROUP, groupName, "Deleted IAM group",
                () -> iamService.provider().deleteGroup(groupName));
    }

    @GetMapping("/groups/{groupName}/members")
    public List<String> listGroupMembers(@PathVariable String groupName) {
        return iamService.provider().listGroupMembers(groupName);
    }

    @PutMapping("/groups/{groupName}/members/{userName}")
    public void addGroupMember(@PathVariable String groupName, @PathVariable String userName,
                               Principal principal) {
        audit.audited(principal, "EDIT", GROUP, groupName, "Added " + userName + " to the group",
                () -> iamService.provider().addUserToGroup(userName, groupName));
    }

    @DeleteMapping("/groups/{groupName}/members/{userName}")
    public void removeGroupMember(@PathVariable String groupName, @PathVariable String userName,
                                  Principal principal) {
        audit.audited(principal, "EDIT", GROUP, groupName, "Removed " + userName + " from the group",
                () -> iamService.provider().removeUserFromGroup(userName, groupName));
    }

    // ── Policies ─────────────────────────────────────────────────────────

    @GetMapping("/policies")
    public List<IamPolicySummary> listPolicies() {
        return iamService.provider().listPolicies();
    }

    // A policy id is an ARN, which contains slashes. Tomcat rejects encoded slashes inside a path
    // segment, so the single-policy routes take the id as a query parameter instead.

    @GetMapping("/policy")
    public Map<String, String> getPolicy(@RequestParam String id) {
        Map<String, String> body = new HashMap<>();
        body.put("document", iamService.provider().getPolicyDocument(id));
        return body;
    }

    @PostMapping("/policies")
    public IamPolicySummary createPolicy(@RequestBody PolicyRequest request, Principal principal) {
        String name = requireText(request == null ? null : request.name(), "Policy name");
        String document = requireText(request == null ? null : request.document(), "Policy document");
        requireValidJson(document);
        return audit.auditedCall(principal, "CREATE", POLICY, name, "Created IAM policy",
                () -> iamService.provider().createPolicy(name, document));
    }

    @PutMapping("/policy")
    public void updatePolicy(@RequestParam String id, @RequestBody String document,
                             Principal principal) {
        requireValidJson(document);
        audit.audited(principal, "EDIT", POLICY, id, "Updated IAM policy",
                () -> iamService.provider().updatePolicy(id, document));
    }

    @DeleteMapping("/policy")
    public void deletePolicy(@RequestParam String id, Principal principal) {
        audit.audited(principal, "DELETE", POLICY, id, "Deleted IAM policy",
                () -> iamService.provider().deletePolicy(id));
    }

    // ── Policy attachments ───────────────────────────────────────────────

    @GetMapping("/users/{userName}/policies")
    public List<IamPolicySummary> listUserPolicies(@PathVariable String userName) {
        return iamService.provider().listAttachedPolicies(IamTarget.user(userName));
    }

    @PostMapping("/users/{userName}/policies")
    public void attachUserPolicy(@PathVariable String userName, @RequestBody PolicyRefRequest request,
                                 Principal principal) {
        attach(IamTarget.user(userName), USER, request, principal);
    }

    @DeleteMapping("/users/{userName}/policies")
    public void detachUserPolicy(@PathVariable String userName, @RequestParam String policyId,
                                 Principal principal) {
        detach(IamTarget.user(userName), USER, policyId, principal);
    }

    @GetMapping("/groups/{groupName}/policies")
    public List<IamPolicySummary> listGroupPolicies(@PathVariable String groupName) {
        return iamService.provider().listAttachedPolicies(IamTarget.group(groupName));
    }

    @PostMapping("/groups/{groupName}/policies")
    public void attachGroupPolicy(@PathVariable String groupName, @RequestBody PolicyRefRequest request,
                                  Principal principal) {
        attach(IamTarget.group(groupName), GROUP, request, principal);
    }

    @DeleteMapping("/groups/{groupName}/policies")
    public void detachGroupPolicy(@PathVariable String groupName, @RequestParam String policyId,
                                  Principal principal) {
        detach(IamTarget.group(groupName), GROUP, policyId, principal);
    }

    // ── Inline policies ──────────────────────────────────────────────────

    @GetMapping("/users/{userName}/inline-policies")
    public List<String> listUserInlinePolicies(@PathVariable String userName) {
        return iamService.provider().listInlinePolicies(IamTarget.user(userName));
    }

    @GetMapping("/users/{userName}/inline-policy")
    public Map<String, String> getUserInlinePolicy(@PathVariable String userName,
                                                   @RequestParam String name) {
        return inlineDocument(IamTarget.user(userName), name);
    }

    @PutMapping("/users/{userName}/inline-policy")
    public void putUserInlinePolicy(@PathVariable String userName, @RequestParam String name,
                                    @RequestBody String document, Principal principal) {
        putInline(IamTarget.user(userName), USER, name, document, principal);
    }

    @DeleteMapping("/users/{userName}/inline-policy")
    public void deleteUserInlinePolicy(@PathVariable String userName, @RequestParam String name,
                                       Principal principal) {
        deleteInline(IamTarget.user(userName), USER, name, principal);
    }

    @GetMapping("/groups/{groupName}/inline-policies")
    public List<String> listGroupInlinePolicies(@PathVariable String groupName) {
        return iamService.provider().listInlinePolicies(IamTarget.group(groupName));
    }

    @GetMapping("/groups/{groupName}/inline-policy")
    public Map<String, String> getGroupInlinePolicy(@PathVariable String groupName,
                                                    @RequestParam String name) {
        return inlineDocument(IamTarget.group(groupName), name);
    }

    @PutMapping("/groups/{groupName}/inline-policy")
    public void putGroupInlinePolicy(@PathVariable String groupName, @RequestParam String name,
                                     @RequestBody String document, Principal principal) {
        putInline(IamTarget.group(groupName), GROUP, name, document, principal);
    }

    @DeleteMapping("/groups/{groupName}/inline-policy")
    public void deleteGroupInlinePolicy(@PathVariable String groupName, @RequestParam String name,
                                        Principal principal) {
        deleteInline(IamTarget.group(groupName), GROUP, name, principal);
    }

    private Map<String, String> inlineDocument(IamTarget target, String policyName) {
        Map<String, String> body = new HashMap<>();
        body.put("document", iamService.provider().getInlinePolicy(target, policyName));
        return body;
    }

    private void putInline(IamTarget target, String resourceType, String policyName,
                           String document, Principal principal) {
        String name = requireText(policyName, "Policy name");
        requireValidJson(document);
        audit.audited(principal, "EDIT", resourceType, target.name(), "Set inline policy " + name,
                () -> iamService.provider().putInlinePolicy(target, name, document));
    }

    private void deleteInline(IamTarget target, String resourceType, String policyName,
                              Principal principal) {
        String name = requireText(policyName, "Policy name");
        audit.audited(principal, "EDIT", resourceType, target.name(), "Removed inline policy " + name,
                () -> iamService.provider().deleteInlinePolicy(target, name));
    }

    private void attach(IamTarget target, String resourceType, PolicyRefRequest request,
                        Principal principal) {
        String policyId = requireText(request == null ? null : request.policyId(), "Policy");
        audit.audited(principal, "EDIT", resourceType, target.name(), "Attached policy " + policyId,
                () -> iamService.provider().attachPolicy(target, policyId));
    }

    private void detach(IamTarget target, String resourceType, String policyId, Principal principal) {
        audit.audited(principal, "EDIT", resourceType, target.name(), "Detached policy " + policyId,
                () -> iamService.provider().detachPolicy(target, policyId));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String username(Principal principal) {
        return principal == null ? null : principal.getName();
    }

    private static String requireName(NameRequest request, String what) {
        return requireText(request == null ? null : request.name(), what);
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        return value.trim();
    }

    /** Rejects a malformed policy document with a 400 before it ever reaches the provider. */
    private void requireValidJson(String json) {
        try {
            jsonMapper.readTree(json);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException(
                    "Policy document is not valid JSON: " + ex.getOriginalMessage());
        }
    }

    public record NameRequest(String name) {
    }

    public record PolicyRequest(String name, String document) {
    }

    public record PolicyRefRequest(String policyId) {
    }
}
