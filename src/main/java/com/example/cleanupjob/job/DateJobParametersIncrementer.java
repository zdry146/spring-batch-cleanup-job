package com.example.cleanupjob.job;

import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.parameters.JobParametersIncrementer;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Produces a date-based identifying parameter so:
 * - Each calendar day's CronJob run is a distinct JobInstance.
 * - A manual re-launch the same day (e.g., {@code kubectl delete job && kubectl apply}
 *   after a failure) reuses the same JobInstance and Spring Batch restarts from the
 *   last committed chunk instead of starting from scratch.
 */
public class DateJobParametersIncrementer implements JobParametersIncrementer {

    public static final String RUN_DATE = "run.date";

    @Override
    public JobParameters getNext(JobParameters parameters) {
        Set<JobParameter<?>> merged = new HashSet<>(parameters.parameters());
        merged.removeIf(p -> RUN_DATE.equals(p.name()));
        merged.add(new JobParameter<>(RUN_DATE, LocalDate.now(), LocalDate.class, true));
        return new JobParameters(merged);
    }
}
