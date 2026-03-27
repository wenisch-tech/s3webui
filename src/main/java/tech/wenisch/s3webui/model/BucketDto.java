package tech.wenisch.s3webui.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class BucketDto {
    private String name;
    private Instant creationDate;
}
