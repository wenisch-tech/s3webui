package tech.wenisch.s3webui.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import tech.wenisch.s3webui.model.iam.IamTarget;
import tech.wenisch.s3webui.service.iam.AwsIamProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CephIamResponseInterceptorTest {

    private HttpServer server;
    private IamClient client;
    private AwsIamProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respondLikeCeph);
        server.start();

        client = IamClient.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret")))
                .overrideConfiguration(config -> config
                        .addExecutionInterceptor(new CephIamResponseInterceptor()))
                .build();
        provider = new AwsIamProvider(client);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void cephUserPolicyResultsAreNotLostWhenMetadataComesFirst() {
        assertEquals("inline-one", provider.listInlinePolicies(IamTarget.user("alice")).get(0));

        var attached = provider.listAttachedPolicies(IamTarget.user("alice"));
        assertEquals(1, attached.size());
        assertEquals("AmazonS3ReadOnlyAccess", attached.get(0).name());
        assertEquals("arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess", attached.get(0).id());

        assertEquals("{\"Version\":\"2012-10-17\"}",
                provider.getInlinePolicy(IamTarget.user("alice"), "inline-one"));
    }

    @Test
    void anAwsOrderedResponseIsLeftIntact() {
        String xml = response("ListUserPolicies", "<PolicyNames><member>inline-one</member></PolicyNames>", false);

        assertEquals(xml, CephIamResponseInterceptor.moveMetadataAfterResult(xml, "ListUserPoliciesResult"));
    }

    private void respondLikeCeph(HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String body;
        if (request.contains("Action=ListUserPolicies")) {
            body = response("ListUserPolicies",
                    "<PolicyNames><member>inline-one</member></PolicyNames><IsTruncated>false</IsTruncated>", true);
        } else if (request.contains("Action=ListAttachedUserPolicies")) {
            body = response("ListAttachedUserPolicies",
                    "<AttachedPolicies><member><PolicyName>AmazonS3ReadOnlyAccess</PolicyName>"
                            + "<PolicyArn>arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess</PolicyArn>"
                            + "</member></AttachedPolicies><IsTruncated>false</IsTruncated>", true);
        } else if (request.contains("Action=GetUserPolicy")) {
            body = response("GetUserPolicy",
                    "<PolicyName>inline-one</PolicyName><UserName>alice</UserName>"
                            + "<PolicyDocument>%7B%22Version%22%3A%222012-10-17%22%7D</PolicyDocument>", true);
        } else {
            throw new AssertionError("Unexpected IAM request: " + request);
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/xml");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String response(String operation, String resultBody, boolean metadataFirst) {
        String metadata = "<ResponseMetadata><RequestId>request-1</RequestId></ResponseMetadata>";
        String result = '<' + operation + "Result>" + resultBody + "</" + operation + "Result>";
        return '<' + operation + "Response xmlns=\"https://iam.amazonaws.com/doc/2010-05-08/\">"
                + (metadataFirst ? metadata + result : result + metadata)
                + "</" + operation + "Response>";
    }
}
