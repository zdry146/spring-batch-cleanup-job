# Spring Batch Cleanup Job - Agent Instructions

## Project Overview

This is a Kubernetes-deployable Spring Batch job that soft-deletes unpublished posts older than 30 days.

## Key Files

- `src/main/java/com/example/cleanupjob/job/CleanupJobConfig.java` - Job and step configuration
- `src/main/java/com/example/cleanupjob/processor/SoftDeleteProcessor.java` - Item processor
- `src/main/java/com/example/cleanupjob/reader/` - Item readers (UnpublishedPostReader, DeletedPostReader)
- `src/main/java/com/example/cleanupjob/writer/BatchSoftDeleteWriter.java` - Item writer
- `k8s/job.yaml` - Kubernetes job manifest (manual trigger)
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

## Testing

```bash
# Unit tests (23 tests)
mvn test

# E2E test - run job and verify
bash scripts/run-and-verify.sh

# E2E test - error injection and restart
bash scripts/test-error-injection.sh
```

## Important Notes

- Uses Spring Boot 4.0.5 with Spring Batch
- Java 21 required
- Uses PostgreSQL database at 192.168.232.128
- batch-jobs namespace in Kubernetes
- Docker image: cleanup-batch:1.0.0