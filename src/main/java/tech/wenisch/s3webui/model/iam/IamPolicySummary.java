package tech.wenisch.s3webui.model.iam;

/**
 * A standalone, attachable policy. {@code id} is what every other call takes: the ARN on AWS, the
 * policy name on providers that have no ARNs.
 *
 * @param editable false for provider-owned policies (AWS-managed ones) that cannot be changed or
 *                 deleted, only attached
 */
public record IamPolicySummary(String id, String name, String arn, boolean editable) {
}
