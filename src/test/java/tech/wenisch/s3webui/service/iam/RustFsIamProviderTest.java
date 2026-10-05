package tech.wenisch.s3webui.service.iam;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.iam.IamClient;
import tech.wenisch.s3webui.config.IamConfig;
import tech.wenisch.s3webui.model.iam.IamTarget;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RustFsIamProviderTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, StubResponse> responses = new HashMap<>();
    private final List<RecordedRequest> requests = new ArrayList<>();
    private HttpServer server;
    private RustFsAdminClient client;
    private RustFsIamProvider provider;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        var settings = new S3ConnectionSettingsService.EffectiveS3Settings(
                "ROOTKEY", "root-secret-key", endpoint, "us-east-1", false);
        client = new RustFsAdminClient(settings, json);
        provider = new RustFsIamProvider(client);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void signedProbeDetectsRustFsWhenHealthIsUnavailable() {
        respond("GET", "/health", 404, "");
        respond("GET", "/rustfs/admin/v3/list-users", 200, "{}");

        assertTrue(client.isRustFs());
        RecordedRequest probe = request("GET", "/rustfs/admin/v3/list-users");
        String authorization = probe.header("Authorization");
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=ROOTKEY/"));
        assertTrue(authorization.contains("/us-east-1/s3/aws4_request"));
        assertEquals("UNSIGNED-PAYLOAD", probe.header("x-amz-content-sha256"));
    }

    @Test
    void unauthorisedNativeRouteIsStillDetectedAsRustFs() {
        respond("GET", "/health", 200, "{\"status\":\"ok\"}");
        respond("GET", "/rustfs/admin/v3/list-users", 403,
                "<Error><Code>AccessDenied</Code><Message>access denied</Message></Error>");

        assertTrue(client.isRustFs());
    }

    @Test
    void absentNativeRouteIsNotDetectedAsRustFs() {
        // A generic S3-compatible server may expose its own healthy/versioned status document.
        // Only RustFS's explicit service marker is sufficient without a native route probe.
        respond("GET", "/health", 200, "{\"status\":\"ok\",\"version\":\"other-s3\"}");
        respond("GET", "/rustfs/admin/v3/list-users", 404,
                "<Error><Code>NoSuchBucket</Code><Message>Not found</Message></Error>");

        assertFalse(client.isRustFs());
    }

    @Test
    void queryValuesUseSigV4CompatiblePercentEncoding() {
        respond("GET", "/rustfs/admin/v3/group?group=team%20a%26b", 200,
                "{\"name\":\"team a&b\",\"members\":[],\"policy\":\"\"}");

        client.get("/group", Map.of("group", "team a&b"));

        request("GET", "/rustfs/admin/v3/group?group=team%20a%26b");
    }

    @Test
    void configurationAutomaticallySelectsRustFsFromItsHealthResponse() {
        respond("GET", "/health", 200,
                "{\"status\":\"ok\",\"service\":\"rustfs-endpoint\",\"version\":\"1.0.0\"}");
        S3ConnectionSettingsService settingsService = mock(S3ConnectionSettingsService.class);
        when(settingsService.getEffectiveSettingsOrThrow()).thenReturn(settings());

        IamProvider selected = new IamConfig().iamProvider(mock(IamClient.class), settingsService, json);

        assertTrue(selected instanceof RustFsIamProvider);
    }

    @Test
    void configurationFallsBackToAwsIamWhenRustFsRoutesAreAbsent() {
        respond("GET", "/health", 404, "");
        respond("GET", "/rustfs/admin/v3/list-users", 404,
                "<Error><Code>NoSuchBucket</Code><Message>Not found</Message></Error>");
        S3ConnectionSettingsService settingsService = mock(S3ConnectionSettingsService.class);
        when(settingsService.getEffectiveSettingsOrThrow()).thenReturn(settings());

        IamProvider selected = new IamConfig().iamProvider(mock(IamClient.class), settingsService, json);

        assertTrue(selected instanceof AwsIamProvider);
    }

    @Test
    void capabilitiesDescribeRustFsAndDisableInlinePolicies() {
        respond("GET", "/rustfs/admin/v3/list-users", 200, "{}");

        var capabilities = provider.capabilities();

        assertTrue(capabilities.available());
        assertEquals("RustFS Admin API", capabilities.provider());
        assertTrue(capabilities.groups());
        assertTrue(capabilities.accessKeys());
        assertTrue(capabilities.managedPolicies());
        assertFalse(capabilities.inlinePolicies());
        assertThrows(UnsupportedOperationException.class,
                () -> provider.listInlinePolicies(IamTarget.user("alice")));
    }

    @Test
    void unauthorisedCapabilityProbeExplainsRequiredAdminPermission() {
        respond("GET", "/rustfs/admin/v3/list-users", 403,
                "<Error><Code>AccessDenied</Code><Message>access denied</Message></Error>");

        var capabilities = provider.capabilities();

        assertFalse(capabilities.available());
        assertTrue(capabilities.reason().contains("RustFS admin API"));
        assertTrue(capabilities.reason().contains("admin:*"));
    }

    @Test
    void creatingAUserGeneratesAndReturnsItsPrimaryCredential() throws Exception {
        respond("PUT", "/rustfs/admin/v3/add-user?accessKey=alice", 200, "");

        var key = provider.createUser("alice");

        assertEquals("alice", key.userName());
        assertEquals("alice", key.accessKeyId());
        assertEquals(40, key.secretAccessKey().length());
        JsonNode body = json.readTree(request("PUT", "/rustfs/admin/v3/add-user?accessKey=alice").body());
        assertEquals("enabled", body.path("status").asText());
        assertEquals(key.secretAccessKey(), body.path("secretKey").asText());
        assertNotEquals("root-secret-key", key.secretAccessKey());
    }

    @Test
    void officialListShapesMapToTheExistingIamModels() {
        respond("GET", "/rustfs/admin/v3/list-users", 200,
                "{\"bob\":{\"status\":\"enabled\"},\"alice\":{\"status\":\"enabled\",\"updatedAt\":\"2026-01-02T03:04:05Z\"}}");
        respond("GET", "/rustfs/admin/v3/groups", 200, "[\"ops\",\"devs\"]");
        respond("GET", "/rustfs/admin/v3/list-canned-policies", 200,
                "{\"readonly\":{\"Version\":\"2012-10-17\"},\"custom\":{\"Version\":\"2012-10-17\"}}");
        respond("GET", "/rustfs/admin/v3/user-info?accessKey=alice", 200,
                "{\"status\":\"enabled\",\"policyName\":\"readonly,custom\"}");
        respond("GET", "/rustfs/admin/v3/group?group=devs", 200,
                "{\"name\":\"devs\",\"members\":[],\"policy\":\"custom\"}");

        assertEquals(List.of("alice", "bob"), provider.listUsers().stream().map(user -> user.userName()).toList());
        assertEquals(List.of("devs", "ops"), provider.listGroups().stream().map(group -> group.groupName()).toList());
        var policies = provider.listPolicies();
        assertEquals(List.of("custom", "readonly"), policies.stream().map(policy -> policy.name()).toList());
        assertTrue(policies.get(0).editable());
        assertFalse(policies.get(1).editable());
        assertEquals(List.of("custom", "readonly"), provider.listAttachedPolicies(IamTarget.user("alice"))
                .stream().map(policy -> policy.name()).toList());
        assertEquals(List.of("custom"), provider.listAttachedPolicies(IamTarget.group("devs"))
                .stream().map(policy -> policy.name()).toList());
    }

    @Test
    void emptyGroupsAndPolicyAttachmentsUseRustFsNativeBodies() {
        respond("PUT", "/rustfs/admin/v3/update-group-members", 200, "");
        respond("POST", "/rustfs/admin/v3/idp/builtin/policy/attach", 200, "{}");
        respond("POST", "/rustfs/admin/v3/idp/builtin/policy/detach", 200, "{}");

        provider.createGroup("devs");
        provider.attachPolicy(IamTarget.user("alice"), "readonly");
        provider.detachPolicy(IamTarget.group("devs"), "readonly");

        JsonNode group = jsonNode(request("PUT", "/rustfs/admin/v3/update-group-members").body());
        assertEquals("devs", group.path("group").asText());
        assertEquals(0, group.path("members").size());
        assertFalse(group.path("isRemove").asBoolean());
        assertEquals("enabled", group.path("groupStatus").asText());
        List<RecordedRequest> associations = requests.stream()
                .filter(recorded -> recorded.pathAndQuery().contains("/idp/builtin/policy/"))
                .toList();
        assertTrue(associations.get(0).body().contains("\"user\":\"alice\""));
        assertTrue(associations.get(1).body().contains("\"group\":\"devs\""));
    }

    @Test
    void accessKeyListingProtectsPrimaryAndIncludesServiceAccounts() {
        respond("GET", "/rustfs/admin/v3/user-info?accessKey=alice", 200,
                "{\"status\":\"enabled\",\"updatedAt\":\"2026-01-02T03:04:05Z\"}");
        respond("GET", "/rustfs/admin/v3/list-service-accounts?user=alice", 200,
                "{\"accounts\":[{\"accessKey\":\"SVC2\",\"accountStatus\":\"on\",\"parentUser\":\"alice\",\"impliedPolicy\":true}]}");

        var keys = provider.listAccessKeys("alice");

        assertEquals(2, keys.size());
        assertTrue(keys.get(0).primary());
        assertFalse(keys.get(0).deletable());
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), keys.get(0).createdAt());
        assertEquals("SVC2", keys.get(1).accessKeyId());
        assertTrue(keys.get(1).deletable());
        assertThrows(IllegalArgumentException.class, () -> provider.deleteAccessKey("alice", "alice"));
    }

    @Test
    void additionalKeysAreInheritedPolicyServiceAccounts() {
        respond("PUT", "/rustfs/admin/v3/add-service-account", 200,
                "{\"credentials\":{\"accessKey\":\"SVC1\",\"secretKey\":\"service-secret\"}}");
        respond("DELETE", "/rustfs/admin/v3/delete-service-account?accessKey=SVC1", 200, "");

        var key = provider.createAccessKey("alice");
        provider.deleteAccessKey("alice", "SVC1");

        assertEquals("SVC1", key.accessKeyId());
        assertEquals("service-secret", key.secretAccessKey());
        assertEquals("{\"targetUser\":\"alice\"}",
                request("PUT", "/rustfs/admin/v3/add-service-account").body());
    }

    @Test
    void deletingAGroupRemovesMembersAndPoliciesFirst() {
        respond("GET", "/rustfs/admin/v3/group?group=devs", 200,
                "{\"name\":\"devs\",\"members\":[\"alice\",\"bob\"],\"policy\":\"readonly,custom\"}");
        respond("PUT", "/rustfs/admin/v3/update-group-members", 200, "");
        respond("POST", "/rustfs/admin/v3/idp/builtin/policy/detach", 200, "{}");
        respond("DELETE", "/rustfs/admin/v3/group/devs", 200, "");

        provider.deleteGroup("devs");

        JsonNode membership = jsonNode(request("PUT", "/rustfs/admin/v3/update-group-members").body());
        assertTrue(membership.path("isRemove").asBoolean());
        assertEquals(2, membership.path("members").size());
        assertEquals(2, requests.stream()
                .filter(request -> request.pathAndQuery().equals("/rustfs/admin/v3/idp/builtin/policy/detach"))
                .count());
        assertEquals("DELETE", request("DELETE", "/rustfs/admin/v3/group/devs").method());
    }

    @Test
    void policyDocumentsAreSentRawAndBuiltInsStayProtected() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        respond("PUT", "/rustfs/admin/v3/add-canned-policy?name=bucket-reader", 200, "");
        respond("GET", "/rustfs/admin/v3/info-canned-policy?name=bucket-reader", 200,
                "{\"Policy\":" + policy + ",\"UpdateDate\":\"2026-01-01T00:00:00Z\"}");

        provider.createPolicy("bucket-reader", policy);

        assertEquals(policy, request("PUT", "/rustfs/admin/v3/add-canned-policy?name=bucket-reader").body());
        assertEquals(policy, provider.getPolicyDocument("bucket-reader"));
        assertThrows(IllegalArgumentException.class, () -> provider.updatePolicy("readonly", policy));
        assertThrows(IllegalArgumentException.class, () -> provider.deletePolicy("consoleAdmin"));
    }

    @Test
    void deletingAPolicyDetachesEveryEntityBeforeRemoval() {
        respond("GET", "/rustfs/admin/v3/idp/builtin/policy-entities?policy=custom", 200,
                "{\"policyMappings\":[{\"policy\":\"custom\",\"users\":[\"alice\"],\"groups\":[\"devs\"]}]}");
        respond("POST", "/rustfs/admin/v3/idp/builtin/policy/detach", 200, "{}");
        respond("DELETE", "/rustfs/admin/v3/remove-canned-policy?name=custom", 200, "");

        provider.deletePolicy("custom");

        List<RecordedRequest> detach = requests.stream()
                .filter(request -> request.pathAndQuery().equals("/rustfs/admin/v3/idp/builtin/policy/detach"))
                .toList();
        assertEquals(2, detach.size());
        assertTrue(detach.stream().map(RecordedRequest::body).anyMatch(body -> body.contains("\"user\":\"alice\"")));
        assertTrue(detach.stream().map(RecordedRequest::body).anyMatch(body -> body.contains("\"group\":\"devs\"")));
        request("DELETE", "/rustfs/admin/v3/remove-canned-policy?name=custom");
    }

    @Test
    void providerErrorsKeepStatusCodeAndServerMessage() {
        respond("DELETE", "/rustfs/admin/v3/remove-user?accessKey=missing", 404,
                "<Error><Code>NoSuchUser</Code><Message>The user does not exist</Message></Error>");

        RustFsAdminException error = assertThrows(
                RustFsAdminException.class, () -> provider.deleteUser("missing"));

        assertEquals(404, error.statusCode());
        assertEquals("NoSuchUser", error.errorCode());
        assertEquals("The user does not exist", error.getMessage());
    }

    private void respond(String method, String pathAndQuery, int status, String body) {
        responses.put(method + " " + pathAndQuery, new StubResponse(status, body));
    }

    private S3ConnectionSettingsService.EffectiveS3Settings settings() {
        return new S3ConnectionSettingsService.EffectiveS3Settings(
                "ROOTKEY", "root-secret-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "us-east-1", false);
    }

    private RecordedRequest request(String method, String pathAndQuery) {
        return requests.stream()
                .filter(request -> request.method().equals(method) && request.pathAndQuery().equals(pathAndQuery))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No request recorded for " + method + " " + pathAndQuery));
    }

    private JsonNode jsonNode(String body) {
        try {
            return json.readTree(body);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        if (exchange.getRequestURI().getRawQuery() != null) {
            path += "?" + exchange.getRequestURI().getRawQuery();
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new RecordedRequest(
                exchange.getRequestMethod(), path, exchange.getRequestHeaders(), body));
        StubResponse response = responses.getOrDefault(
                exchange.getRequestMethod() + " " + path,
                new StubResponse(500, "Missing stub for " + exchange.getRequestMethod() + " " + path));
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        if (!response.body().isEmpty()) {
            exchange.getResponseHeaders().set("Content-Type", response.body().startsWith("<")
                    ? "application/xml" : "application/json");
        }
        exchange.sendResponseHeaders(response.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record StubResponse(int status, String body) {
    }

    private record RecordedRequest(
            String method,
            String pathAndQuery,
            com.sun.net.httpserver.Headers headers,
            String body) {
        String header(String name) {
            return headers.getFirst(name);
        }
    }
}
