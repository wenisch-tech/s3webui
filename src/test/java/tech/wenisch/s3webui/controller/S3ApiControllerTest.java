package tech.wenisch.s3webui.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tech.wenisch.s3webui.service.AuditHistoryService;
import tech.wenisch.s3webui.service.S3Service;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ApiControllerTest {

    @Test
    void uploadMultipartPartReadsBodyWithoutContentLength() throws Exception {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        when(s3Service.uploadPart(any(), any(), any(), any(Integer.class), any(byte[].class)))
                .thenReturn("\"etag-123\"");

        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService);

        byte[] requestBody = "part-body".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(requestBody);

        var response = controller.uploadMultipartPart("bucket-a", "folder/file.bin", "upload-1", 2,
                (HttpServletRequest) request);

        assertEquals("\"etag-123\"", response.getBody().get("eTag"));
        verify(s3Service).uploadPart(eq("bucket-a"), eq("folder/file.bin"), eq("upload-1"), eq(2), eq(requestBody));
    }
}