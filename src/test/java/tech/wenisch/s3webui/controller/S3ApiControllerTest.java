package tech.wenisch.s3webui.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tech.wenisch.s3webui.model.BucketDto;
import tech.wenisch.s3webui.model.CorsRuleDto;
import tech.wenisch.s3webui.service.AuditHistoryService;
import tech.wenisch.s3webui.service.BucketVisibilityService;
import tech.wenisch.s3webui.service.S3Service;
import tech.wenisch.s3webui.service.GlobalSearchService;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    void successfulObjectMutationInvalidatesTheSessionSearchCatalog() {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        GlobalSearchService searchService = mock(GlobalSearchService.class);
        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);
        controller.setGlobalSearchService(searchService);

        controller.deleteObject("bucket-a", "folder/file.txt", null);

        verify(searchService).invalidate();
    }

    @Test
    void bucketListIsFilteredUnlessEveryBucketIsRequested() {
        S3Service s3Service = mock(S3Service.class);
        BucketVisibilityService visibility = mock(BucketVisibilityService.class);
        var all = List.of(BucketDto.builder().name("open").build(), BucketDto.builder().name("locked").build());
        when(s3Service.listBuckets()).thenReturn(all);
        when(visibility.filter(all)).thenReturn(List.of(all.get(0)));
        S3ApiController controller = new S3ApiController(s3Service, mock(AuditHistoryService.class), jsonMapper);
        controller.setBucketVisibilityService(visibility);

        assertThat(controller.listBuckets(false).getBody()).extracting(BucketDto::getName).containsExactly("open");
        assertThat(controller.listBuckets(true).getBody()).extracting(BucketDto::getName)
                .containsExactly("open", "locked");
    }

    @Test
    void changingABucketPolicyForgetsCachedBucketVisibility() {
        BucketVisibilityService visibility = mock(BucketVisibilityService.class);
        S3ApiController controller = new S3ApiController(mock(S3Service.class), mock(AuditHistoryService.class), jsonMapper);
        controller.setBucketVisibilityService(visibility);

        controller.updateBucketPolicy("b", "{\"Statement\": []}", null);
        controller.deleteBucketPolicy("b", null);

        verify(visibility, org.mockito.Mockito.times(2)).invalidate();
    }

    @Test
    void bucketObjectSearchReturnsMetadataWithoutAllowingResponseCaching() {
        S3Service s3Service = mock(S3Service.class);
        GlobalSearchService searchService = mock(GlobalSearchService.class);
        var page = new GlobalSearchService.BucketSearchPage(
                List.of(), 0, Instant.parse("2026-10-06T09:00:00Z"));
        when(searchService.searchBucket("bucket-a", "docs/", "report")).thenReturn(page);
        S3ApiController controller = new S3ApiController(s3Service, mock(AuditHistoryService.class), jsonMapper);
        controller.setGlobalSearchService(searchService);

        var response = controller.searchObjects("bucket-a", "report", "docs/");

        assertEquals(page, response.getBody());
        org.junit.jupiter.api.Assertions.assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(searchService).searchBucket("bucket-a", "docs/", "report");
    }

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

    @Test
    void updateBucketVersioningRecordsAnEditInAuditHistory() {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);

        controller.updateBucketVersioning("b", new S3ApiController.VersioningRequest(true, 3, 1), null);

        verify(s3Service).putBucketVersioning("b", true, 3, 1);
        verify(auditHistoryService).record(
                isNull(), eq("EDIT"), eq("BUCKET"), eq("b"), isNull(), eq("Updated versioning configuration"));
    }

    @Test
    void invalidVersioningRequestIsRejectedWithoutCallingS3() {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);

        assertThrows(IllegalArgumentException.class, () -> controller.updateBucketVersioning(
                "b", new S3ApiController.VersioningRequest(true, 0, 1), null));
        assertThrows(IllegalArgumentException.class, () -> controller.updateBucketVersioning(
                "b", new S3ApiController.VersioningRequest(true, 3, 0), null));
        assertThrows(IllegalArgumentException.class, () -> controller.updateBucketVersioning(
                "b", new S3ApiController.VersioningRequest(null, null, null), null));
        verify(s3Service, never()).putBucketVersioning(any(), anyBoolean(), any(), any());
    }

    @Test
    void restoreAndDeleteVersionAreAudited() {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);

        controller.restoreObjectVersion("b", "k", "v1", null);
        controller.deleteObjectVersion("b", "k", "v2", null);

        verify(s3Service).restoreObjectVersion("b", "k", "v1");
        verify(s3Service).deleteObjectVersion("b", "k", "v2");
        verify(auditHistoryService).record(
                isNull(), eq("EDIT"), eq("OBJECT"), eq("b"), eq("k"), eq("Restored version v1"));
        verify(auditHistoryService).record(
                isNull(), eq("DELETE"), eq("OBJECT"), eq("b"), eq("k"), eq("Permanently deleted version v2"));
    }

    @Test
    void updateBucketLifecycleIsAuditedAndValidated() {
        S3Service s3Service = mock(S3Service.class);
        AuditHistoryService auditHistoryService = mock(AuditHistoryService.class);
        S3ApiController controller = new S3ApiController(s3Service, auditHistoryService, jsonMapper);
        var ok = new tech.wenisch.s3webui.model.LifecycleRuleDto("r", true, "", 30, null, List.of(), null, null, null, true);

        controller.updateBucketLifecycle("b", new S3ApiController.LifecycleConfig(List.of(ok)), null);

        verify(s3Service).putBucketLifecycle("b", List.of(ok));
        verify(auditHistoryService).record(
                isNull(), eq("EDIT"), eq("BUCKET"), eq("b"), isNull(), eq("Updated lifecycle rules"));

        var duplicate = new S3ApiController.LifecycleConfig(List.of(ok, ok));
        assertThrows(IllegalArgumentException.class, () -> controller.updateBucketLifecycle("b", duplicate, null));
        var conflicting = new tech.wenisch.s3webui.model.LifecycleRuleDto("c", true, "", 5, true, List.of(), null, null, null, true);
        assertThrows(IllegalArgumentException.class, () -> controller.updateBucketLifecycle(
                "b", new S3ApiController.LifecycleConfig(List.of(conflicting)), null));
        var managed = new tech.wenisch.s3webui.model.LifecycleRuleDto(
                S3Service.RETENTION_RULE_ID, true, "", 5, null, List.of(), null, null, null, true);
        assertThrows(IllegalArgumentException.class, () -> controller.updateBucketLifecycle(
                "b", new S3ApiController.LifecycleConfig(List.of(managed)), null));
        var badClass = new tech.wenisch.s3webui.model.LifecycleRuleDto("t", true, "", null, null,
                List.of(new tech.wenisch.s3webui.model.LifecycleRuleDto.Transition(5, "BOGUS")), null, null, null, true);
        assertThrows(IllegalArgumentException.class, () -> controller.updateBucketLifecycle(
                "b", new S3ApiController.LifecycleConfig(List.of(badClass)), null));
    }
}
