package tech.wenisch.s3webui.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class S3ObjectDto {
    private String key;
    private String name;
    private String prefix;
    private long size;
    private Instant lastModified;
    private String storageClass;
    private boolean directory;
}
