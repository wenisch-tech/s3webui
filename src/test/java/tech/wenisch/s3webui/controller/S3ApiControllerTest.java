package tech.wenisch.s3webui.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tech.wenisch.s3webui.model.CorsRuleDto;
import tech.wenisch.s3webui.service.AuditHistoryService;
import tech.wenisch.s3webui.service.S3Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ApiControllerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void createBucketRecordsFailureInAuditHistory() {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        RuntimeException failure = new RuntimeException("Bucket already exists");
        doThrow(failure).when(s3Service).createBucket("existing-bucket");

        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);

        assertThrows(RuntimeException.class, () -> controller.createBucket("existing-bucket", null));
        verify(auditHistoryService).record(
                eq(null),
                eq("CREATE"),
                eq("BUCKET"),
                eq("existing-bucket"),
                eq(null),
                eq("Failed: Bucket already exists"));
    }

    @Test
    void uploadMultipartPartReadsBodyWithoutContentLength() throws Exception {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        when(s3Service.uploadPart(any(), any(), any(), any(Integer.class), any(byte[].class)))
                .thenReturn("\"etag-123\"");

        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);

        byte[] requestBody = "part-body".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(requestBody);

        var response = controller.uploadMultipartPart("bucket-a", "folder/file.bin", "upload-1", 2,
                (HttpServletRequest) request);

        assertEquals("\"etag-123\"", response.getBody().get("eTag"));
        verify(s3Service).uploadPart(eq("bucket-a"), eq("folder/file.bin"), eq("upload-1"), eq(2), eq(requestBody));
    }

    // ── Bucket policy / CORS ──────────────────────────────────────────────

    @Test
    void updateBucketPolicyRecordsAnEditInAuditHistory() {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);

        controller.updateBucketPolicy("b", "{\"Version\":\"2012-10-17\",\"Statement\":[]}", null);

        verify(s3Service).putBucketPolicy("b", "{\"Version\":\"2012-10-17\",\"Statement\":[]}");
        verify(auditHistoryService).record(
                isNull(), eq("EDIT"), eq("BUCKET"), eq("b"), isNull(), eq("Updated bucket policy"));
    }

    @Test
    void updateBucketPolicyRecordsFailureAndRethrows() {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        doThrow(new RuntimeException("MalformedPolicy")).when(s3Service).putBucketPolicy(any(), any());
        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);

        assertThrows(RuntimeException.class, () -> controller.updateBucketPolicy("b", "{}", null));
        verify(auditHistoryService).record(
                isNull(), eq("EDIT"), eq("BUCKET"), eq("b"), isNull(), startsWith("Failed: "));
    }

    @Test
    void malformedPolicyJsonIsRejectedWithoutCallingS3() {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);

        assertThrows(IllegalArgumentException.class,
                () -> controller.updateBucketPolicy("b", "{ not json", null));
        verify(s3Service, never()).putBucketPolicy(any(), any());
        verify(auditHistoryService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteBucketPolicyRecordsADeleteInAuditHistory() {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);

        controller.deleteBucketPolicy("b", null);

        verify(s3Service).deleteBucketPolicy("b");
        verify(auditHistoryService).record(
                isNull(), eq("DELETE"), eq("BUCKET"), eq("b"), isNull(), eq("Removed bucket policy"));
    }

    @Test
    void updateBucketCorsRecordsAnEditInAuditHistory() {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);
        var rules = List.of(new CorsRuleDto(null, List.of("GET"), List.of("*"), List.of("*"), null, 3000));

        controller.updateBucketCors("b", new S3ApiController.CorsConfigRequest(rules), null);

        verify(s3Service).putBucketCors("b", rules);
        verify(auditHistoryService).record(
                isNull(), eq("EDIT"), eq("BUCKET"), eq("b"), isNull(), eq("Updated CORS configuration"));
    }

    @Test
    void emptyCorsRuleListIsRejectedWithoutCallingS3() {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);

        assertThrows(IllegalArgumentException.class,
                () -> controller.updateBucketCors("b", new S3ApiController.CorsConfigRequest(List.of()), null));
        verify(s3Service, never()).putBucketCors(any(), any());
    }

    @Test
    void deleteBucketCorsRecordsADeleteInAuditHistory() {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);

        controller.deleteBucketCors("b", null);

        verify(s3Service).deleteBucketCors("b");
        verify(auditHistoryService).record(
                isNull(), eq("DELETE"), eq("BUCKET"), eq("b"), isNull(), eq("Removed CORS configuration"));
    }
}
