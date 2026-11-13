package tech.wenisch.s3webui.model;

import java.util.List;

/**
 * A simplified lifecycle rule. Rules using constructs the editor cannot represent (tag/size filters,
 * date-based actions, noncurrent transitions, rules managed by the versioning dialog) are returned with
 * {@code editable=false} and are preserved unchanged when the configuration is saved.
 */
public record LifecycleRuleDto(
        String id,
        Boolean enabled,
        String prefix,
        Integer expirationDays,
        Boolean expireDeleteMarkers,
        List<Transition> transitions,
        Integer noncurrentDays,
        Integer noncurrentKeepVersions,
        Integer abortIncompleteMultipartDays,
        boolean editable) {

    public record Transition(Integer days, String storageClass) {
    }
}
