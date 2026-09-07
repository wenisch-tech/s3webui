package tech.wenisch.s3webui.model;

/**
 * A key as offered to a user in the picker and the navbar switcher.
 */
public record S3CredentialView(
        String id,
        String name,
        String endpointUrl,
        String region,
        boolean builtIn
) {
}
