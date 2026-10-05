package tech.wenisch.s3webui.service.iam;

import tech.wenisch.s3webui.model.iam.IamAccessKey;
import tech.wenisch.s3webui.model.iam.IamAccessKeySummary;
import tech.wenisch.s3webui.model.iam.IamCapabilities;
import tech.wenisch.s3webui.model.iam.IamGroup;
import tech.wenisch.s3webui.model.iam.IamPolicySummary;
import tech.wenisch.s3webui.model.iam.IamTarget;
import tech.wenisch.s3webui.model.iam.IamUser;
import tools.jackson.databind.JsonNode;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Implements S3 Web UI's IAM contract through the native RustFS admin API. */
public class RustFsIamProvider implements IamProvider {

    private static final Set<String> BUILT_IN_POLICIES = Set.of(
            "readwrite", "readonly", "writeonly", "diagnostics", "consoleAdmin",
            "KMSKeyAdministrator", "KMSKeyUser", "KMSAuditor");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RustFsAdminClient client;
    private IamCapabilities cachedCapabilities;

    public RustFsIamProvider(RustFsAdminClient client) {
        this.client = client;
    }

    @Override
    public IamCapabilities capabilities() {
        if (cachedCapabilities != null) {
            return cachedCapabilities;
        }
        try {
            listUsers();
            cachedCapabilities = new IamCapabilities(
                    true, "RustFS Admin API", null, true, true, true, false);
        } catch (RustFsAdminException ex) {
            String reason = ex.statusCode() == 401 || ex.statusCode() == 403
                    ? "The selected S3 key is not authorised for the RustFS admin API. Use the root key or grant the required admin:* actions."
                    : "RustFS did not answer the IAM capability probe: " + ex.getMessage();
            cachedCapabilities = IamCapabilities.unavailable(reason);
        } catch (RuntimeException ex) {
            cachedCapabilities = IamCapabilities.unavailable(
                    "RustFS did not answer the IAM capability probe: " + rootMessage(ex));
        }
        return cachedCapabilities;
    }

    // ── Users ────────────────────────────────────────────────────────────

    @Override
    public List<IamUser> listUsers() {
        JsonNode users = client.get("/list-users");
        List<IamUser> result = new ArrayList<>();
        users.properties().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> result.add(new IamUser(
                        entry.getKey(), null, instant(entry.getValue().get("updatedAt")))));
        return result;
    }

    @Override
    public IamAccessKey createUser(String userName) {
        String secret = newSecret();
        client.put("/add-user", Map.of("accessKey", userName), Map.of(
                "secretKey", secret,
                "status", "enabled"));
        // In RustFS an IAM user's name is also the access key of its primary credential.
        return new IamAccessKey(userName, userName, secret);
    }

    @Override
    public void deleteUser(String userName) {
        // RustFS removes the user's group memberships, policy mapping, service accounts and STS
        // credentials atomically with the regular identity.
        client.delete("/remove-user", Map.of("accessKey", userName));
    }

    // ── Groups ───────────────────────────────────────────────────────────

    @Override
    public List<IamGroup> listGroups() {
        JsonNode groups = client.get("/groups");
        List<IamGroup> result = new ArrayList<>();
        groups.forEach(group -> result.add(new IamGroup(group.asText(), null, null)));
        result.sort(Comparator.comparing(IamGroup::groupName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    @Override
    public void createGroup(String groupName) {
        updateGroupMembers(groupName, List.of(), false);
    }

    @Override
    public void deleteGroup(String groupName) {
        JsonNode group = group(groupName);
        List<String> members = strings(group.path("members"));
        if (!members.isEmpty()) {
            updateGroupMembers(groupName, members, true);
        }
        for (String policy : commaSeparated(group.path("policy").asText())) {
            detachPolicy(IamTarget.group(groupName), policy);
        }
        client.delete("/group/" + pathSegment(groupName), Map.of());
    }

    @Override
    public List<String> listGroupMembers(String groupName) {
        List<String> members = strings(group(groupName).path("members"));
        members.sort(String.CASE_INSENSITIVE_ORDER);
        return members;
    }

    @Override
    public void addUserToGroup(String userName, String groupName) {
        updateGroupMembers(groupName, List.of(userName), false);
    }

    @Override
    public void removeUserFromGroup(String userName, String groupName) {
        updateGroupMembers(groupName, List.of(userName), true);
    }

    private void updateGroupMembers(String groupName, List<String> members, boolean remove) {
        client.put("/update-group-members", Map.of(), Map.of(
                "group", groupName,
                "members", members,
                "isRemove", remove,
                "groupStatus", "enabled"));
    }

    private JsonNode group(String groupName) {
        return client.get("/group", Map.of("group", groupName));
    }

    // ── Policies ─────────────────────────────────────────────────────────

    @Override
    public List<IamPolicySummary> listPolicies() {
        JsonNode policies = client.get("/list-canned-policies");
        List<IamPolicySummary> result = new ArrayList<>();
        policies.properties().stream()
                .map(Map.Entry::getKey)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(RustFsIamProvider::policySummary)
                .forEach(result::add);
        return result;
    }

    @Override
    public String getPolicyDocument(String policyId) {
        JsonNode response = client.get("/info-canned-policy", Map.of("name", policyId));
        JsonNode document = response.has("Policy") ? response.get("Policy") : response;
        return document.toString();
    }

    @Override
    public IamPolicySummary createPolicy(String name, String document) {
        putPolicy(name, document);
        return policySummary(name);
    }

    @Override
    public void updatePolicy(String policyId, String document) {
        requireEditable(policyId);
        putPolicy(policyId, document);
    }

    @Override
    public void deletePolicy(String policyId) {
        requireEditable(policyId);
        JsonNode entities = client.get("/idp/builtin/policy-entities", Map.of("policy", policyId));
        JsonNode mappings = entities.path("policyMappings");
        mappings.forEach(mapping -> {
            strings(mapping.path("users")).forEach(user -> detachPolicy(IamTarget.user(user), policyId));
            strings(mapping.path("groups")).forEach(group -> detachPolicy(IamTarget.group(group), policyId));
        });
        client.delete("/remove-canned-policy", Map.of("name", policyId));
    }

    @Override
    public List<IamPolicySummary> listAttachedPolicies(IamTarget target) {
        String names = target.type() == IamTarget.Type.USER
                ? client.get("/user-info", Map.of("accessKey", target.name())).path("policyName").asText()
                : group(target.name()).path("policy").asText();
        return commaSeparated(names).stream().map(RustFsIamProvider::policySummary).toList();
    }

    @Override
    public void attachPolicy(IamTarget target, String policyId) {
        client.post("/idp/builtin/policy/attach", association(target, policyId));
    }

    @Override
    public void detachPolicy(IamTarget target, String policyId) {
        client.post("/idp/builtin/policy/detach", association(target, policyId));
    }

    private void putPolicy(String name, String document) {
        // The policy endpoint consumes the document itself, not a JSON wrapper.
        client.put("/add-canned-policy", Map.of("name", name), new RawJson(document));
    }

    private static Map<String, Object> association(IamTarget target, String policyId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policies", List.of(policyId));
        body.put(target.type() == IamTarget.Type.USER ? "user" : "group", target.name());
        return body;
    }

    // ── Inline policies ──────────────────────────────────────────────────

    @Override
    public List<String> listInlinePolicies(IamTarget target) {
        throw inlinePoliciesUnsupported();
    }

    @Override
    public String getInlinePolicy(IamTarget target, String policyName) {
        throw inlinePoliciesUnsupported();
    }

    @Override
    public void putInlinePolicy(IamTarget target, String policyName, String document) {
        throw inlinePoliciesUnsupported();
    }

    @Override
    public void deleteInlinePolicy(IamTarget target, String policyName) {
        throw inlinePoliciesUnsupported();
    }

    // ── Access keys ──────────────────────────────────────────────────────

    @Override
    public List<IamAccessKeySummary> listAccessKeys(String userName) {
        JsonNode user = client.get("/user-info", Map.of("accessKey", userName));
        List<IamAccessKeySummary> keys = new ArrayList<>();
        keys.add(new IamAccessKeySummary(
                userName, user.path("status").asText("enabled"), instant(user.get("updatedAt")), true, false));

        JsonNode response = client.get("/list-service-accounts", Map.of("user", userName));
        response.path("accounts").forEach(account -> keys.add(new IamAccessKeySummary(
                account.path("accessKey").asText(),
                account.path("accountStatus").asText(),
                null,
                false,
                true)));
        keys.subList(1, keys.size()).sort(
                Comparator.comparing(IamAccessKeySummary::accessKeyId, String.CASE_INSENSITIVE_ORDER));
        return keys;
    }

    @Override
    public IamAccessKey createAccessKey(String userName) {
        JsonNode response = client.put(
                "/add-service-account", Map.of(), Map.of("targetUser", userName));
        JsonNode credentials = response.path("credentials");
        return new IamAccessKey(
                userName,
                credentials.path("accessKey").asText(),
                credentials.path("secretKey").asText());
    }

    @Override
    public void deleteAccessKey(String userName, String accessKeyId) {
        if (userName.equals(accessKeyId)) {
            throw new IllegalArgumentException(
                    "The primary RustFS key can only be removed by deleting its IAM user");
        }
        client.delete("/delete-service-account", Map.of("accessKey", accessKeyId));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static IamPolicySummary policySummary(String name) {
        return new IamPolicySummary(name, name, null, !BUILT_IN_POLICIES.contains(name));
    }

    private static void requireEditable(String name) {
        if (BUILT_IN_POLICIES.contains(name)) {
            throw new IllegalArgumentException(
                    "RustFS built-in policies cannot be modified or deleted, only attached");
        }
    }

    private static UnsupportedOperationException inlinePoliciesUnsupported() {
        return new UnsupportedOperationException(
                "RustFS does not support AWS-style inline policies; use a named policy instead");
    }

    private static String newSecret() {
        byte[] random = new byte[30];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static List<String> commaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(",")).stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static Instant instant(JsonNode value) {
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String pathSegment(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /** Marker that asks the transport to send an already-validated JSON policy document raw. */
    public record RawJson(String json) {
    }
}
