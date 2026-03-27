package tech.wenisch.s3webui.model;

import lombok.Data;

import java.util.List;

@Data
public class CompleteMultipartRequest {
    private String uploadId;
    private String key;
    private List<PartETag> parts;

    @Data
    public static class PartETag {
        private int partNumber;
        private String eTag;
    }
}
