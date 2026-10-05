package tech.wenisch.s3webui.service.iam;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import tech.wenisch.s3webui.service.S3ConnectionSettingsService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small SigV4 client for RustFS's JSON administration API. */
public class RustFsAdminClient {

    private static final String ADMIN_PREFIX = "/rustfs/admin/v3";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final Pattern XML_CODE = Pattern.compile("<Code>([^<]+)</Code>");
    private static final Pattern XML_MESSAGE = Pattern.compile("<Message>([^<]+)</Message>");

    private final URI endpoint;
    private final String region;
    private final AwsBasicCredentials credentials;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    public RustFsAdminClient(S3ConnectionSettingsService.EffectiveS3Settings settings, JsonMapper jsonMapper) {
        this.endpoint = URI.create(stripTrailingSlash(settings.endpointUrl()));
        this.region = settings.region();
        this.credentials = AwsBasicCredentials.create(settings.accessKey(), settings.secretKey());
        this.jsonMapper = jsonMapper;
        this.httpClient = createHttpClient(settings.insecureSkipTlsVerify());
    }

    /**
     * Detects the native RustFS endpoint without confusing MinIO or an ordinary S3 bucket path
     * with RustFS. The public health response is the strong signal; a signed native request is the
     * fallback for deployments that disabled or minimized that response.
     */
    public boolean isRustFs() {
        RawResponse health = send(false, SdkHttpMethod.GET, "/health", Map.of(), null, true);
        if (isRustFsHealth(health)) {
            return true;
        }

        RawResponse probe = send(true, SdkHttpMethod.GET, ADMIN_PREFIX + "/list-users", Map.of(), null, true);
        if (probe.statusCode() >= 200 && probe.statusCode() < 300) {
            return parseJson(probe.body()).isObject();
        }
        // Native admin routes distinguish an unauthorised RustFS key from an endpoint that simply
        // does not implement the API. Keeping it as RustFS gives the operator the useful error.
        return probe.statusCode() == 400 || probe.statusCode() == 401 || probe.statusCode() == 403;
    }

    public JsonNode get(String path) {
        return get(path, Map.of());
    }

    public JsonNode get(String path, Map<String, String> query) {
        return request(SdkHttpMethod.GET, path, query, null);
    }

    public JsonNode put(String path, Map<String, String> query, Object body) {
        return request(SdkHttpMethod.PUT, path, query, writeJson(body));
    }

    public JsonNode putRawJson(String path, Map<String, String> query, String body) {
        return request(SdkHttpMethod.PUT, path, query, body.getBytes(StandardCharsets.UTF_8));
    }

    public JsonNode post(String path, Object body) {
        return request(SdkHttpMethod.POST, path, Map.of(), writeJson(body));
    }

    public JsonNode delete(String path, Map<String, String> query) {
        return request(SdkHttpMethod.DELETE, path, query, null);
    }

    private JsonNode request(SdkHttpMethod method, String path, Map<String, String> query, byte[] body) {
        RawResponse response = send(true, method, ADMIN_PREFIX + path, query, body, false);
        if (response.body().length == 0) {
            return null;
        }
        return parseJson(response.body());
    }

    private RawResponse send(boolean signed, SdkHttpMethod method, String path, Map<String, String> query,
                             byte[] body, boolean allowErrors) {
        URI uri = uri(path, query);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);

        if (signed) {
            SdkHttpFullRequest.Builder sdkRequest = SdkHttpFullRequest.builder()
                    .uri(uri)
                    .method(method)
                    // RustFS follows S3's SigV4 convention and accepts the explicit unsigned
                    // payload marker used by its own admin clients.
                    .putHeader("x-amz-content-sha256", "UNSIGNED-PAYLOAD");
            if (body != null) {
                sdkRequest.putHeader("Content-Type", "application/json")
                        .contentStreamProvider(() -> new ByteArrayInputStream(body));
            }
            SdkHttpFullRequest signedRequest = Aws4Signer.create().sign(sdkRequest.build(), Aws4SignerParams.builder()
                    .awsCredentials(credentials)
                    .signingName("s3")
                    .signingRegion(Region.of(region))
                    .doubleUrlEncode(false)
                    .normalizePath(false)
                    .build());
            signedRequest.headers().forEach((name, values) -> copyHeader(request, name, values));
        }

        if (body != null && !signed) {
            request.header("Content-Type", "application/json");
        }
        request.method(method.name(), body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));

        try {
            HttpResponse<byte[]> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            RawResponse raw = new RawResponse(response.statusCode(), response.headers().map(), response.body());
            if (!allowErrors && (raw.statusCode() < 200 || raw.statusCode() >= 300)) {
                throw error(raw);
            }
            return raw;
        } catch (IOException ex) {
            throw new IllegalStateException("RustFS admin request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RustFS admin request was interrupted", ex);
        }
    }

    private boolean isRustFsHealth(RawResponse response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return false;
        }
        try {
            JsonNode body = parseJson(response.body());
            return "rustfs-endpoint".equals(body.path("service").asText());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private RustFsAdminException error(RawResponse response) {
        String text = new String(response.body(), StandardCharsets.UTF_8);
        String code = match(XML_CODE, text);
        String message = match(XML_MESSAGE, text);
        try {
            JsonNode json = jsonMapper.readTree(text);
            code = firstNonBlank(json.path("code").asText(), json.path("Code").asText(), code);
            message = firstNonBlank(json.path("message").asText(), json.path("Message").asText(), message);
        } catch (JacksonException ignored) {
            // RustFS normally uses the S3 XML error envelope; JSON is accepted for future versions.
        }
        message = firstNonBlank(message, code, "RustFS admin request failed with HTTP " + response.statusCode());
        return new RustFsAdminException(response.statusCode(), code, message);
    }

    private URI uri(String path, Map<String, String> query) {
        StringBuilder value = new StringBuilder(endpoint.toString()).append(path);
        if (!query.isEmpty()) {
            value.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    value.append('&');
                }
                first = false;
                value.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
        }
        return URI.create(value.toString());
    }

    private byte[] writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsBytes(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Could not encode RustFS admin request", ex);
        }
    }

    private JsonNode parseJson(byte[] value) {
        try {
            return jsonMapper.readTree(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("RustFS returned an invalid JSON response", ex);
        }
    }

    private static void copyHeader(HttpRequest.Builder request, String name, List<String> values) {
        // java.net.http owns these transport headers. Host is still part of the signature and the
        // client emits the same authority from the URI.
        if (name.equalsIgnoreCase("host") || name.equalsIgnoreCase("content-length")) {
            return;
        }
        values.forEach(value -> request.header(name, value));
    }

    private static HttpClient createHttpClient(boolean insecure) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (!insecure) {
            return builder.build();
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("");
            return builder.sslContext(context).sslParameters(parameters).build();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not configure the RustFS TLS client", ex);
        }
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String match(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record RawResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
    }
}
