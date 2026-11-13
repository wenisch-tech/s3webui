package tech.wenisch.s3webui.model;

import java.time.Instant;

public record ObjectVersionDto(
        String versionId,
        boolean latest,
        boolean deleteMarker,
        long size,
        Instant lastModified,
        String storageClass) {
}
