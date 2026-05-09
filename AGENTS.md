# Spring Batch Cleanup Job - Agent Instructions

## Project Overview

This is a Kubernetes-deployable Spring Batch job that soft-deletes unpublished posts older than 30 days.

## Key Files

- `src/main/java/com/example/cleanupjob/job/CleanupJobConfig.java` - Job and step configuration
- `src/main/java/com/example/cleanupjob/processor/SoftDeleteProcessor.java` - Item processor
- `src/main/java/com/example/cleanupjob/reader/UnpublishedPostReader.java` - Reads unpublished posts older than 30 days
- `src/main/java/com/example/cleanupjob/reader/DeletedPostReader.java` - Reads already-deleted posts
- `src/main/java/com/example/cleanupjob/writer/BatchSoftDeleteWriter.java` - Batch soft-delete writer
- `src/main/java/com/example/cleanupjob/model/Post.java` - JPA entity
- `src/main/java/com/example/cleanupjob/repository/PostRepository.java` - Spring Data JPA repository
- `k8s/job.yaml` - Kubernetes Job manifest (manual trigger)
- `k8s/cronjob.yaml` - Kubernetes CronJob manifest (scheduled daily at midnight)
- `scripts/` - E2E test scripts

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| DB_HOST | 192.168.232.128 | PostgreSQL host |
| DB_DATABASE | testdb | Database name |
| DB_USERNAME | postgres | Database user |
| DB_PASSWORD | (from secret) | Database password |
| ERROR_INJECTION_STEP1 | false | Inject error in Step 1 |
| ERROR_INJECTION_STEP2 | false | Inject error in Step 2 |
| ERROR_TYPE | PERMANENT | Error type (PERMANENT or TRANSIENT) |

## Job Structure

The job has two steps:
1. **cleanupStep** - Soft-deletes unpublished posts older than 30 days (chunk size: 100, retry: 3)
2. **processDeletedPostsStep** - Processes already-deleted posts (chunk size: 100, retry: 2)

Both steps use `SQLException` retry with fault tolerance.

## Testing

```bash
# Unit tests (23 tests)
mvn test

# E2E test - run job and verify
bash scripts/run-and-verify.sh

# E2E test - error injection and restart
bash scripts/test-error-injection.sh
```

## Technology Stack

- Spring Boot 4.0.5
- Spring Batch 6.0.3
- Java 21
- PostgreSQL
- JPA/Hibernate
- Docker container
- Kubernetes (batch-jobs namespace)
- Docker image: cleanup-batch:1.0.0