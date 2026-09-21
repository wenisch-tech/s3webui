package tech.wenisch.s3webui.config;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.iam.model.GetUserPolicyRequest;
import software.amazon.awssdk.services.iam.model.ListAttachedUserPoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListUserPoliciesRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Normalises the three Ceph RGW user-policy responses whose XML elements are emitted in an order
 * the AWS SDK does not accept.
 *
 * <p>Ceph Squid writes {@code ResponseMetadata} before the operation's {@code *Result}. AWS and
 * Ceph's group-policy operations put the result first. The SDK treats the Ceph response as a
 * successful call but silently leaves every result field empty. Moving the metadata block behind
 * the result before unmarshalling makes these responses usable without changing valid AWS
 * responses.</p>
 */
public final class CephIamResponseInterceptor implements ExecutionInterceptor {

    private static final String METADATA_OPEN = "<ResponseMetadata>";
    private static final String METADATA_CLOSE = "</ResponseMetadata>";

    @Override
    public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                            ExecutionAttributes executionAttributes) {
        String resultElement = resultElement(context);
        if (resultElement == null || !context.httpResponse().isSuccessful() || context.responseBody().isEmpty()) {
            return context.responseBody();
        }

        try {
            byte[] original = context.responseBody().orElseThrow().readAllBytes();
            String xml = new String(original, StandardCharsets.UTF_8);
            String normalised = moveMetadataAfterResult(xml, resultElement);
            return Optional.of(new ByteArrayInputStream(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to normalise the IAM response", ex);
        }
    }

    private static String resultElement(Context.ModifyHttpResponse context) {
        if (context.request() instanceof ListUserPoliciesRequest) {
            return "ListUserPoliciesResult";
        }
        if (context.request() instanceof ListAttachedUserPoliciesRequest) {
            return "ListAttachedUserPoliciesResult";
        }
        if (context.request() instanceof GetUserPolicyRequest) {
            return "GetUserPolicyResult";
        }
        return null;
    }

    static String moveMetadataAfterResult(String xml, String resultElement) {
        int metadataStart = xml.indexOf(METADATA_OPEN);
        int metadataEnd = xml.indexOf(METADATA_CLOSE, metadataStart);
        int resultStart = xml.indexOf('<' + resultElement + '>');
        if (metadataStart < 0 || metadataEnd < 0 || resultStart < 0 || metadataStart > resultStart) {
            return xml;
        }

        metadataEnd += METADATA_CLOSE.length();
        String metadata = xml.substring(metadataStart, metadataEnd);
        String withoutMetadata = xml.substring(0, metadataStart) + xml.substring(metadataEnd);
        String resultClose = "</" + resultElement + ">";
        int resultEnd = withoutMetadata.indexOf(resultClose);
        if (resultEnd < 0) {
            return xml;
        }

        int insertAt = resultEnd + resultClose.length();
        return withoutMetadata.substring(0, insertAt) + metadata + withoutMetadata.substring(insertAt);
    }
}
