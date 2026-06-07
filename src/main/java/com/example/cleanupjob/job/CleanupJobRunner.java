package com.example.cleanupjob.job;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.UnexpectedJobExecutionException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Replaces Spring Boot's default {@code JobLauncherApplicationRunner} (we disable
 * it via {@code spring.batch.job.enabled=false}) so the same-day manual run is a
 * graceful no-op when the CronJob already completed.
 *
 * <p>The {@code DateJobParametersIncrementer} is correct for restart-after-failure
 * (delete+reapply on a FAILED instance resumes from the last committed chunk), but
 * it makes a same-day manual run crash the JVM when the CronJob already
 * COMPLETED: {@code JobOperator.startNextInstance} throws
 * {@link JobInstanceAlreadyCompleteException}, k8s restarts the pod, same error,
 * {@code CrashLoopBackOff}. We swallow that one exception and exit 0 — the day is
 * done. Real failures still propagate.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.batch.job.enabled", havingValue = "false", matchIfMissing = false)
@RequiredArgsConstructor
public class CleanupJobRunner implements ApplicationRunner {

    private final JobOperator jobOperator;
    private final Job cleanupUnpublishedPostsJob;

    @Override
    public void run(ApplicationArguments args) {
        try {
            // Spring Batch 6: startNextInstance(Job) is the non-deprecated overload.
            // It wraps JobInstanceAlreadyCompleteException (and other "shouldn't
            // happen" cases on a fresh instance) in UnexpectedJobExecutionException;
            // the original exception is preserved as the cause.
            jobOperator.startNextInstance(cleanupUnpublishedPostsJob);
        } catch (UnexpectedJobExecutionException e) {
            if (e.getCause() instanceof JobInstanceAlreadyCompleteException) {
                log.info("Job instance for today is already COMPLETED; nothing to do " +
                        "(manual run after a successful cron run is a no-op).");
            } else {
                throw e;
            }
        }
    }
}
