package tech.wenisch.s3webui.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import tech.wenisch.s3webui.service.AuditHistoryService;

import java.security.Principal;

/**
 * The record-success / record-failure-and-rethrow pattern the API controllers share, so a mutating
 * endpoint reads as one line of intent rather than eight of boilerplate.
 */
@Component
@RequiredArgsConstructor
public class AuditSupport {

    private final AuditHistoryService auditHistoryService;

    /** Runs {@code action}, auditing either the success detail or the failure, then rethrows. */
    public void audited(Principal principal, String action, String resourceType, String resourceName,
                        String successDetail, Runnable body) {
        try {
            body.run();
            record(principal, action, resourceType, resourceName, successDetail);
        } catch (RuntimeException ex) {
            record(principal, action, resourceType, resourceName, "Failed: " + resolveMessage(ex));
            throw ex;
        }
    }

    /** As {@link #audited}, for an action that produces a value. */
    public <T> T auditedCall(Principal principal, String action, String resourceType, String resourceName,
                             String successDetail, java.util.function.Supplier<T> body) {
        try {
            T result = body.get();
            record(principal, action, resourceType, resourceName, successDetail);
            return result;
        } catch (RuntimeException ex) {
            record(principal, action, resourceType, resourceName, "Failed: " + resolveMessage(ex));
            throw ex;
        }
    }

    private void record(Principal principal, String action, String resourceType, String resourceName,
                        String detail) {
        auditHistoryService.record(
                principal == null ? null : principal.getName(),
                action,
                resourceType,
                null,
                resourceName,
                detail);
    }

    static String resolveMessage(Throwable ex) {
        if (ex instanceof AwsServiceException awsEx
                && awsEx.awsErrorDetails() != null
                && awsEx.awsErrorDetails().errorMessage() != null
                && !awsEx.awsErrorDetails().errorMessage().isBlank()) {
            return awsEx.awsErrorDetails().errorMessage();
        }
        String message = ex.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        Throwable cause = ex.getCause();
        if (cause != null && cause != ex && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return ex.getClass().getSimpleName();
    }
}
