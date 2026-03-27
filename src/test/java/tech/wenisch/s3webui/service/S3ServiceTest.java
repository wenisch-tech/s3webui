package tech.wenisch.s3webui.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tech.wenisch.s3webui.model.CompleteMultipartRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ServiceTest {

    @Test
    void uploadPartPreservesQuotedEtag() {
        S3Client s3Client = mock(S3Client.class);
        S3Presigner s3Presigner = mock(S3Presigner.class);
        S3Service s3Service = new S3Service(s3Client, s3Presigner);

        when(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("\"etag-1\"").build());

        String eTag = s3Service.uploadPart("bucket-a", "file.bin", "upload-1", 1, new byte[] {1, 2, 3});

        assertEquals("\"etag-1\"", eTag);
    }

    @Test
        void completeMultipartUploadUsesListedEtagsSortedByPartNumber() {
        S3Client s3Client = mock(S3Client.class);
        S3Presigner s3Presigner = mock(S3Presigner.class);
        S3Service s3Service = new S3Service(s3Client, s3Presigner);

        when(s3Client.listParts(any(ListPartsRequest.class)))
            .thenReturn(ListPartsResponse.builder()
                .isTruncated(false)
                .parts(
                    Part.builder().partNumber(2).eTag("\"server-etag-2\"").build(),
                    Part.builder().partNumber(1).eTag("\"server-etag-1\"").build()
                )
                .build());

        when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenReturn(CompleteMultipartUploadResponse.builder().build());

        CompleteMultipartRequest.PartETag part2 = new CompleteMultipartRequest.PartETag();
        part2.setPartNumber(2);
        part2.setETag("\"etag-2\"");

        CompleteMultipartRequest.PartETag part1 = new CompleteMultipartRequest.PartETag();
        part1.setPartNumber(1);
        part1.setETag("\"etag-1\"");

        s3Service.completeMultipartUpload("bucket-a", "file.bin", "upload-1", List.of(part2, part1));

        ArgumentCaptor<CompleteMultipartUploadRequest> requestCaptor = ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(s3Client).listParts(any(ListPartsRequest.class));
        verify(s3Client).completeMultipartUpload(requestCaptor.capture());

        List<CompletedPart> completedParts = requestCaptor.getValue().multipartUpload().parts();
        assertEquals(1, completedParts.get(0).partNumber());
        assertEquals("\"server-etag-1\"", completedParts.get(0).eTag());
        assertEquals(2, completedParts.get(1).partNumber());
        assertEquals("\"server-etag-2\"", completedParts.get(1).eTag());
    }
}