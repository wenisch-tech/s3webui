package tech.wenisch.s3webui.service;

import tech.wenisch.s3webui.model.BucketDto;
import tech.wenisch.s3webui.model.CompleteMultipartRequest;
import tech.wenisch.s3webui.model.S3ObjectDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    public List<BucketDto> listBuckets() {
        return s3Client.listBuckets().buckets().stream()
                .map(b -> BucketDto.builder()
                        .name(b.name())
                        .creationDate(b.creationDate())
                        .build())
                .collect(Collectors.toList());
    }

    public void createBucket(String name) {
        s3Client.createBucket(CreateBucketRequest.builder().bucket(name).build());
    }

    public void deleteBucket(String name) {
        s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(name).build());
    }

    public List<S3ObjectDto> listObjects(String bucket, String prefix) {
        String normalizedPrefix = (prefix == null || prefix.isBlank()) ? "" : prefix;
        if (!normalizedPrefix.isEmpty() && !normalizedPrefix.endsWith("/")) {
            normalizedPrefix = normalizedPrefix + "/";
        }

        var request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(normalizedPrefix)
                .delimiter("/")
                .build();

        var result = new ArrayList<S3ObjectDto>();

        var response = s3Client.listObjectsV2(request);

        // Add "virtual folders" (common prefixes)
        for (CommonPrefix cp : response.commonPrefixes()) {
            String fullPrefix = cp.prefix();
            String folderName = fullPrefix.substring(normalizedPrefix.length());
            if (folderName.endsWith("/")) {
                folderName = folderName.substring(0, folderName.length() - 1);
            }
            result.add(S3ObjectDto.builder()
                    .key(fullPrefix)
                    .name(folderName)
                    .prefix(fullPrefix)
                    .directory(true)
                    .build());
        }

        // Add objects
        for (S3Object obj : response.contents()) {
            String key = obj.key();
            if (key.equals(normalizedPrefix)) {
                continue; // skip the prefix itself
            }
            String name = key.substring(normalizedPrefix.length());
            result.add(S3ObjectDto.builder()
                    .key(key)
                    .name(name)
                    .size(obj.size())
                    .lastModified(obj.lastModified())
                    .storageClass(obj.storageClassAsString())
                    .directory(false)
                    .build());
        }

        return result;
    }

    public ResponseInputStream<GetObjectResponse> getObject(String bucket, String key) {
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }

    public void putObject(String bucket, String key, InputStream inputStream, long contentLength, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .contentLength(contentLength)
                        .build(),
                RequestBody.fromInputStream(inputStream, contentLength));
    }

    public void deleteObject(String bucket, String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }

    public void deleteObjects(String bucket, List<String> keys) {
        List<ObjectIdentifier> identifiers = keys.stream()
                .map(k -> ObjectIdentifier.builder().key(k).build())
                .collect(Collectors.toList());
        s3Client.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(identifiers).build())
                .build());
    }

    public void renameObject(String bucket, String oldKey, String newKey) {
        s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket)
                .sourceKey(oldKey)
                .destinationBucket(bucket)
                .destinationKey(newKey)
                .build());
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(oldKey)
                .build());
    }

    // ── Multipart upload ────────────────────────────────────────────────────

    public String initiateMultipartUpload(String bucket, String key, String contentType) {
        var response = s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build());
        return response.uploadId();
    }

    public String presignUploadPart(String bucket, String key, String uploadId, int partNumber) {
        PresignedUploadPartRequest presigned = s3Presigner.presignUploadPart(
                UploadPartPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(60))
                        .uploadPartRequest(UploadPartRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .build())
                        .build());
        return presigned.url().toString();
    }

    public void completeMultipartUpload(String bucket, String key, String uploadId,
                                        List<CompleteMultipartRequest.PartETag> parts) {
        List<CompletedPart> completedParts = parts.stream()
                .map(p -> CompletedPart.builder()
                        .partNumber(p.getPartNumber())
                        .eTag(p.getETag())
                        .build())
                .collect(Collectors.toList());

        s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder()
                        .parts(completedParts)
                        .build())
                .build());
    }

    public void abortMultipartUpload(String bucket, String key, String uploadId) {
        s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .build());
    }

    public Map<String, String> getBucketStats(String bucket) {
        long totalSize = 0;
        long objectCount = 0;

        var paginator = s3Client.listObjectsV2Paginator(
                ListObjectsV2Request.builder().bucket(bucket).build());

        for (var page : paginator) {
            for (S3Object obj : page.contents()) {
                totalSize += obj.size();
                objectCount++;
            }
        }

        return Map.of(
                "objectCount", String.valueOf(objectCount),
                "totalSize", String.valueOf(totalSize)
        );
    }
}
