package tech.wenisch.s3webui.model;

import java.time.Instant;

/** A file or derived virtual folder returned by the global object search. */
public record GlobalSearchResult(
        Type type,
        String bucket,
        String key,
        String name,
        String parentPrefix,
        Long size,
        Instant lastModified
) {
    public enum Type {
        FILE,
        FOLDER
    }
}
