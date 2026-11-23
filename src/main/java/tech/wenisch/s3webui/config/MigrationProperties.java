package tech.wenisch.s3webui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Tuning for server-side migrations ({@code app.migration.*}). */
@Component
@ConfigurationProperties(prefix = "app.migration")
public class MigrationProperties {

    /** Migrations that run at the same time; further ones wait in the queue. */
    private int maxConcurrentJobs = 2;

    /** Objects copied in parallel within one migration. */
    private int threadsPerJob = 4;

    /** Objects up to this size (MiB) are copied with one request, larger ones as multipart uploads. */
    private int multipartThresholdMb = 64;

    /** Size of one multipart part (MiB, at least 5). */
    private int partSizeMb = 16;

    /** Finished jobs are removed from the history after this many days. */
    private int retentionDays = 30;

    public int getMaxConcurrentJobs() {
        return maxConcurrentJobs;
    }

    public void setMaxConcurrentJobs(int maxConcurrentJobs) {
        this.maxConcurrentJobs = maxConcurrentJobs;
    }

    public int getThreadsPerJob() {
        return threadsPerJob;
    }

    public void setThreadsPerJob(int threadsPerJob) {
        this.threadsPerJob = threadsPerJob;
    }

    public int getMultipartThresholdMb() {
        return multipartThresholdMb;
    }

    public void setMultipartThresholdMb(int multipartThresholdMb) {
        this.multipartThresholdMb = multipartThresholdMb;
    }

    public int getPartSizeMb() {
        return partSizeMb;
    }

    public void setPartSizeMb(int partSizeMb) {
        this.partSizeMb = partSizeMb;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}
