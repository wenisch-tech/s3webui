package tech.wenisch.s3webui.model;

/**
 * A key with its secret resolved, ready to build an S3 client from.
 */
public record ResolvedCredential(
        String id,
        String name,
        String accessKey,
        String secretKey,
        String endpointUrl,
        String region,
        boolean insecureSkipTlsVerify
) {
}
