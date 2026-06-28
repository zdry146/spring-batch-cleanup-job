package com.example.cleanupjob.diag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full Spring context just to make {@link BeanGraphDumper} fire.
 * Reads the resulting {@code .codegraph/spring-beans.json} back to confirm
 * BeanGraphDumper ran and produced something meaningful.
 *
 * <p>Why this test exists: the production app is a CronJob that runs to
 * completion and exits; we need a repeatable way to materialize the dump
 * during development. This test is the trigger.
 *
 * <p>Note: this test does not assert the dump's contents; it only asserts
 * the file exists and is non-trivial. The contents are checked by the
 * shell-side {@code codegraph-import-spring.py}.
 */
@SpringBootTest(classes = com.example.cleanupjob.CleanupJobApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.batch.job.enabled=false")
class BeanGraphDumperTest {

    @Test
    void dumpsSpringBeansToCodegraphDir() throws Exception {
        Path out = Paths.get(".codegraph", "spring-beans.json");
        // BeanGraphDumper fires on ContextRefreshedEvent; @SpringBootTest guarantees
        // the listener has run by the time this method body executes.
        assertThat(Files.exists(out)).as("BeanGraphDumper should have written " + out).isTrue();
        long size = Files.size(out);
        assertThat(size)
            .as("spring-beans.json should be at least 1KB; %d bytes is too small", size)
            .isGreaterThan(1024);
        System.out.println("[BeanGraphDumperTest] " + out.toAbsolutePath() + " = " + size + " bytes");
    }
}
