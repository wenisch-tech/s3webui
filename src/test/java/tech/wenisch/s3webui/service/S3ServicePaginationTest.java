package tech.wenisch.s3webui.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tech.wenisch.s3webui.model.S3ObjectDto;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ServicePaginationTest {

    @Test
    void listObjectsFollowsContinuationTokensAndCombinesFoldersAndFiles() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                                .isTruncated(true)
                                .nextContinuationToken("next-page")
                                .commonPrefixes(CommonPrefix.builder().prefix("docs/reports/").build())
                                .contents(
                                        S3Object.builder().key("docs/").size(0L).build(),
                                        S3Object.builder().key("docs/first.txt").size(10L).build())
                                .build(),
                        ListObjectsV2Response.builder()
                                .isTruncated(false)
                                .commonPrefixes(CommonPrefix.builder().prefix("docs/z-archive/").build())
                                .contents(S3Object.builder().key("docs/second.txt").size(20L).build())
                                .build());

        List<S3ObjectDto> objects = s3Service.listObjects("bucket-a", "docs");

        assertEquals(List.of("reports", "z-archive", "first.txt", "second.txt"),
                objects.stream().map(S3ObjectDto::getName).toList());
        ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3Client, times(2)).listObjectsV2(captor.capture());
        assertNull(captor.getAllValues().get(0).continuationToken());
        assertEquals("next-page", captor.getAllValues().get(1).continuationToken());
        assertEquals("docs/", captor.getAllValues().get(1).prefix());
        assertEquals("/", captor.getAllValues().get(1).delimiter());
    }

    @Test
    void listObjectsRejectsMissingContinuationTokenOnTruncatedResponse() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().isTruncated(true).build());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> s3Service.listObjects("bucket-a", ""));

        assertEquals("S3 returned an invalid continuation token while listing bucket 'bucket-a' with prefix ''",
                exception.getMessage());
    }

    @Test
    void listObjectsRejectsRepeatedContinuationToken() {
        S3Client s3Client = mock(S3Client.class);
        S3Service s3Service = new S3Service(s3Client, mock(S3Presigner.class));
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                                .isTruncated(true)
                                .nextContinuationToken("same-token")
                                .build(),
                        ListObjectsV2Response.builder()
                                .isTruncated(true)
                                .nextContinuationToken("same-token")
                                .build());

        assertThrows(IllegalStateException.class, () -> s3Service.listObjects("bucket-a", ""));
    }
}
