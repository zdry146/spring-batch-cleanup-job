package com.example.cleanupjob.job;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThatNoException;

@SpringBootTest(classes = com.example.cleanupjob.CleanupJobApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.batch.job.enabled=false")
class CleanupJobRunnerTest {

    @Autowired
    private CleanupJobRunner cleanupJobRunner;

    private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();

    @Test
    void secondSameDayLaunchIsNoOp_evenThoughInstanceIsComplete() {
        // First call: creates a JobInstance for today's run.date and completes
        // (the H2 test schema has no posts, so both steps run with no work).
        cleanupJobRunner.run(NO_ARGS);

        // Second call: the previous instance is COMPLETED. The underlying
        // JobOperator.startNextInstance throws JobInstanceAlreadyCompleteException,
        // which without the fix propagates out of the runner, the JVM exits
        // non-zero, and k8s enters CrashLoopBackOff. The fix swallows that one
        // exception and returns normally so the day is a no-op.
        assertThatNoException()
                .as("manual run on a day whose cron run already completed should be a no-op")
                .isThrownBy(() -> cleanupJobRunner.run(NO_ARGS));
    }
}
