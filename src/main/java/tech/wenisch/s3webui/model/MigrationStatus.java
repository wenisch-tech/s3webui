package tech.wenisch.s3webui.model;

import java.util.EnumSet;
import java.util.Set;

/** Lifecycle of a migration job. */
public enum MigrationStatus {
    QUEUED,
    SCANNING,
    RUNNING,
    CANCELLING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED;

    private static final Set<MigrationStatus> ACTIVE = EnumSet.of(QUEUED, SCANNING, RUNNING, CANCELLING);

    /** True while the job still has work to do (or is winding down). */
    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public static Set<MigrationStatus> activeStatuses() {
        return EnumSet.copyOf(ACTIVE);
    }
}
