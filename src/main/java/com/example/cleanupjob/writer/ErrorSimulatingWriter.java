package com.example.cleanupjob.writer;

import com.example.cleanupjob.model.Post;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A writer that simulates SQL errors for testing Spring Batch retry and restart behavior.
 *
 * Error injection modes:
 * - TRANSIENT: Fails on first attempt, recovers on retry
 * - PERMANENT: Fails on all attempts until retries exhausted
 */
@Slf4j
public class ErrorSimulatingWriter implements ItemWriter<Post> {

    @PersistenceContext
    private EntityManager entityManager;

    // Error injection configuration
    private static final Map<String, Boolean> errorConfig = new ConcurrentHashMap<>();
    private static final Map<String, Integer> chunkErrorCounts = new ConcurrentHashMap<>();

    // Configuration keys
    public static final String ERROR_ON_STEP1 = "ERROR_ON_STEP1";
    public static final String ERROR_ON_STEP2 = "ERROR_ON_STEP2";

    private static final int CHUNK_SIZE = 100;

    /**
     * Enable permanent error on specified step
     */
    public static void enableErrorOnStep(String stepKey) {
        errorConfig.put(stepKey, false); // false = permanent
        chunkErrorCounts.put(stepKey, 0);
        log.info("ErrorSimulatingWriter: PERMANENT error enabled for {}", stepKey);
    }

    /**
     * Enable transient error on specified step (recovers on retry)
     */
    public static void enableTransientErrorOnStep(String stepKey) {
        errorConfig.put(stepKey, true); // true = transient
        chunkErrorCounts.put(stepKey, 0);
        log.info("ErrorSimulatingWriter: TRANSIENT error enabled for {} (recovers on retry)", stepKey);
    }

    /**
     * Disable error injection
     */
    public static void disableError(String stepKey) {
        errorConfig.remove(stepKey);
        chunkErrorCounts.remove(stepKey);
        log.info("ErrorSimulatingWriter: Error disabled for {}", stepKey);
    }

    /**
     * Clear all error configurations
     */
    public static void clearAll() {
        errorConfig.clear();
        chunkErrorCounts.clear();
        log.info("ErrorSimulatingWriter: All errors cleared");
    }

    @Override
    public void write(Chunk<? extends Post> chunk) throws Exception {
        if (chunk.isEmpty()) {
            return;
        }

        // Determine which step based on context (passed via thread local or similar)
        String currentStep = determineCurrentStep();

        if (!errorConfig.containsKey(currentStep)) {
            // No error injection, proceed normally
            normalWrite(chunk);
            return;
        }

        // Count chunks processed with error
        int count = chunkErrorCounts.merge(currentStep, 1, Integer::sum);
        boolean isTransient = errorConfig.get(currentStep);

        if (isTransient) {
            // Transient: fail on first chunk only, succeed on retry
            if (count == 1) {
                log.error("ErrorSimulatingWriter: TRANSIENT error on {} (chunk {}, attempt 1/3) - will recover on retry",
                        currentStep, count);
                throw new SQLException("Simulated TRANSIENT error in " + currentStep);
            } else {
                log.info("ErrorSimulatingWriter: {} recovering from transient error", currentStep);
                normalWrite(chunk);
            }
        } else {
            // Permanent: fail always (after retries exhausted, job will fail)
            log.error("ErrorSimulatingWriter: PERMANENT error on {} (chunk {})", currentStep, count);
            throw new SQLException("Simulated PERMANENT error in " + currentStep);
        }
    }

    private String determineCurrentStep() {
        // In real implementation, this would be determined from context
        // For now, we use a simplified approach
        return ERROR_ON_STEP1;
    }

    private void normalWrite(Chunk<? extends Post> chunk) throws Exception {
        for (Post post : chunk.getItems()) {
            post.setIsDeleted(true);
            entityManager.merge(post);
        }
        entityManager.flush();
        entityManager.clear();
        log.info("ErrorSimulatingWriter: normal write of {} posts", chunk.size());
    }
}