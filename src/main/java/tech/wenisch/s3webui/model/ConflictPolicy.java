package tech.wenisch.s3webui.model;

/** What a migration does with an object that already exists at the destination. */
public enum ConflictPolicy {
    /** Leave the existing destination object untouched. */
    SKIP,
    /** Replace the destination object. */
    OVERWRITE,
    /** Replace the destination object only when the source is newer. */
    NEWER_ONLY
}
