package tech.wenisch.s3webui.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void notImplementedS3ErrorsBecomeAFriendlyMessageWithTheProviderStatus() {
        S3Exception exception = (S3Exception) S3Exception.builder()
                .statusCode(501)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("NotImplemented")
                        .errorMessage("A header you provided implies functionality that is not implemented")
                        .build())
                .build();

        ResponseEntity<String> response = handler.handleS3Exception(exception);

        assertEquals(501, response.getStatusCode().value());
        assertTrue(response.getBody().contains("does not support this operation"),
                "expected a friendly explanation, got: " + response.getBody());
    }

    @Test
    void otherS3ErrorsKeepTheirOriginalMessage() {
        S3Exception exception = (S3Exception) S3Exception.builder()
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("MalformedPolicy")
                        .errorMessage("Policy has invalid resource")
                        .build())
                .build();

        ResponseEntity<String> response = handler.handleS3Exception(exception);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Policy has invalid resource", response.getBody());
    }
}
