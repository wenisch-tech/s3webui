package tech.wenisch.s3webui.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tech.wenisch.s3webui.service.DuplicateBucketException;
import tech.wenisch.s3webui.service.MissingS3ConfigurationException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MissingS3ConfigurationException.class)
    public ResponseEntity<Map<String, String>> handleMissingS3Configuration(MissingS3ConfigurationException exception) {
        return ResponseEntity.status(428).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(DuplicateBucketException.class)
    public ResponseEntity<Map<String, String>> handleDuplicateBucket(DuplicateBucketException exception) {
        return ResponseEntity.status(409).body(Map.of("message", resolveMessage(exception)));
    }

    @ExceptionHandler(S3Exception.class)
    public ResponseEntity<String> handleS3Exception(S3Exception exception) {
        int status = exception.statusCode() > 0 ? exception.statusCode() : 500;
        return ResponseEntity.status(status).body(resolveMessage(exception));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleRuntimeException(RuntimeException exception) {
        return ResponseEntity.status(500).body(resolveMessage(exception));
    }

    private String resolveMessage(Throwable throwable) {
        if (throwable instanceof S3Exception s3ex
                && s3ex.awsErrorDetails() != null
                && s3ex.awsErrorDetails().errorMessage() != null
                && !s3ex.awsErrorDetails().errorMessage().isBlank()) {
            return s3ex.awsErrorDetails().errorMessage();
        }

        String message = throwable.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }

        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }

        return throwable.getClass().getSimpleName();
    }
}
