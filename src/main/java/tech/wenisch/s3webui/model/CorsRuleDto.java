package tech.wenisch.s3webui.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One CORS rule, shaped to match the JSON that {@code aws s3api get-bucket-cors} / {@code put-bucket-cors}
 * use so the same document can be pasted between the CLI and the UI editor. {@code maxAgeSeconds} is a
 * boxed {@link Integer} because Jackson cannot bind a missing JSON property into a primitive record
 * component (see {@code S3SessionController.SelectionRequest}); the list fields may likewise be
 * {@code null} and are null-guarded by the service.
 */
public record CorsRuleDto(
        @JsonProperty("ID") String id,
        @JsonProperty("AllowedMethods") List<String> allowedMethods,
        @JsonProperty("AllowedOrigins") List<String> allowedOrigins,
        @JsonProperty("AllowedHeaders") List<String> allowedHeaders,
        @JsonProperty("ExposeHeaders") List<String> exposeHeaders,
        @JsonProperty("MaxAgeSeconds") Integer maxAgeSeconds
) {
}
