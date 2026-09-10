package tech.wenisch.s3webui.model.iam;

/**
 * A freshly minted key pair. The secret is only ever available at creation time, so this never
 * leaves the service layer - it is written straight into the stored S3 keys.
 */
public record IamAccessKey(String userName, String accessKeyId, String secretAccessKey) {
}
