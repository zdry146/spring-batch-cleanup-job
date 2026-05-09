package com.example.cleanupjob.job;

import com.example.cleanupjob.model.Post;
import com.example.cleanupjob.processor.SoftDeleteProcessor;
import com.example.cleanupjob.reader.DeletedPostReader;
import com.example.cleanupjob.reader.UnpublishedPostReader;
import com.example.cleanupjob.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.SQLException;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class CleanupJobConfig {

    private final PostRepository postRepository;
    private final Environment env;

    @Bean
    public ItemReader<Post> unpublishedPostReader() {
        return new UnpublishedPostReader(postRepository);
    }

    @Bean
    public ItemProcessor<Post, Post> softDeleteProcessor() {
        return new SoftDeleteProcessor();
    }

    /**
     * Writer for Step 1 with error injection based on environment variables
     */
    @Bean
    public ItemWriter<Post> cleanupWriter() {
        return chunk -> {
            if (chunk.isEmpty()) {
                return;
            }

            boolean errorInjection = Boolean.parseBoolean(env.getProperty("ERROR_INJECTION_STEP1", "false"));
            String errorType = env.getProperty("ERROR_TYPE", "PERMANENT");

            // Use System.err for visibility since logger might not work in lambda
            System.err.println("[ERROR INJECTION] cleanupWriter called: errorInjection=" + errorInjection + ", errorType=" + errorType + ", chunkSize=" + chunk.size());

            if (errorInjection) {
                log.error("ERROR INJECTION Step1: errorInjection={}, errorType={}, chunkSize={}",
                        errorInjection, errorType, chunk.size());

                if ("TRANSIENT".equals(errorType)) {
                    log.error("ERROR INJECTION: TRANSIENT error - will retry");
                    throw new SQLException("Simulated TRANSIENT error in Step 1");
                } else {
                    log.error("ERROR INJECTION: PERMANENT error - step will fail");
                    throw new SQLException("Simulated PERMANENT error in Step 1");
                }
            }

            // Normal processing
            for (Post post : chunk.getItems()) {
                post.setIsDeleted(true);
            }
            log.info("cleanupWriter: processed {} posts normally", chunk.size());
        };
    }

    /**
     * Writer for Step 2 with error injection
     */
    @Bean
    public ItemWriter<Post> deletedPostsWriter() {
        return chunk -> {
            if (chunk.isEmpty()) {
                return;
            }

            boolean errorInjection = Boolean.parseBoolean(env.getProperty("ERROR_INJECTION_STEP2", "false"));

            if (errorInjection) {
                log.error("ERROR INJECTION Step2: errorInjection={}, chunkSize={}", errorInjection, chunk.size());
                throw new SQLException("Simulated error in Step 2");
            }

            log.info("deletedPostsWriter: processed {} posts normally", chunk.size());
        };
    }

    @Bean
    public ItemReader<Post> deletedPostReader() {
        return new DeletedPostReader(postRepository);
    }

    @Bean
    public ItemProcessor<Post, Post> deletedPostProcessor() {
        return post -> {
            log.info("Processing deleted post: id={}", post.getId());
            return post;
        };
    }

    /**
     * Step 1: Soft-delete unpublished posts older than 30 days
     */
    @Bean
    public Step cleanupStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager) {
        return new StepBuilder("cleanupStep", jobRepository)
                .<Post, Post>chunk(100)
                .transactionManager(transactionManager)
                .reader(unpublishedPostReader())
                .processor(softDeleteProcessor())
                .writer(cleanupWriter())
                .faultTolerant()
                .retry(SQLException.class)
                .retryLimit(3)
                .build();
    }

    /**
     * Step 2: Process already-deleted posts
     */
    @Bean
    public Step processDeletedPostsStep(JobRepository jobRepository,
                                        PlatformTransactionManager transactionManager) {
        return new StepBuilder("processDeletedPostsStep", jobRepository)
                .<Post, Post>chunk(100)
                .transactionManager(transactionManager)
                .reader(deletedPostReader())
                .processor(deletedPostProcessor())
                .writer(deletedPostsWriter())
                .faultTolerant()
                .retry(SQLException.class)
                .retryLimit(2)
                .build();
    }

    /**
     * Main job with two steps
     */
    @Bean
    public Job cleanupUnpublishedPostsJob(JobRepository jobRepository,
                                         Step cleanupStep,
                                         Step processDeletedPostsStep) {
        return new JobBuilder("cleanupUnpublishedPostsJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(cleanupStep)
                .next(processDeletedPostsStep)
                .build();
    }
}