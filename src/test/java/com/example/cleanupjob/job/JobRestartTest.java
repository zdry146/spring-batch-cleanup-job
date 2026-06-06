package com.example.cleanupjob.job;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.job.parameters.JobParametersIncrementer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = com.example.cleanupjob.CleanupJobApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.batch.job.enabled=false")
class JobRestartTest {

    @Autowired
    private Job cleanupUnpublishedPostsJob;

    @Test
    void jobShouldNotUseRunIdIncrementer_soRestartUsesSameJobInstance() {
        JobParametersIncrementer incrementer = cleanupUnpublishedPostsJob.getJobParametersIncrementer();

        assertThat(incrementer)
                .as("RunIdIncrementer prevents true restart: each launch creates a new JobInstance " +
                    "with a new run.id, so 'kubectl delete job && kubectl apply' becomes a fresh " +
                    "run rather than a restart. Job must instead derive uniqueness from a content " +
                    "parameter (e.g., run.date) so identical params trigger a restart.")
                .isNotInstanceOf(RunIdIncrementer.class);
    }

    @Test
    void incrementerMustAddDateParameter_forCronJobUniqueness() {
        JobParametersIncrementer incrementer = cleanupUnpublishedPostsJob.getJobParametersIncrementer();

        assertThat(incrementer)
                .as("job must have an incrementer so daily CronJob runs are distinct")
                .isNotNull();

        JobParameters params = incrementer.getNext(new JobParameters());

        assertThat(params.parameters())
                .as("incrementer should add a date-based parameter for CronJob uniqueness")
                .extracting(p -> p.name())
                .contains("run.date");
    }
}
