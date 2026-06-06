# Spring Batch Cleanup Job - Kubernetes Deployment

Kubernetes-deployable Spring Batch job for soft-deleting unpublished posts older than 30 days.

## Project Structure

```
spring-batch-cleanup-job/
├── pom.xml                          # Maven configuration
├── Dockerfile                       # Docker image build
├── scripts/
│   ├── insert-test-data.sql        # Insert test data
│   ├── run-and-verify.sh           # Run job and verify (E2E)
│   ├── test-error-injection.sh     # Automated error/retry test (E2E)
│   └── test-restart-behavior.sh    # Restart semantics reference
├── src/main/java/com/example/cleanupjob/
│   ├── CleanupJobApplication.java   # Spring Boot entry point
│   ├── job/                        # Job and step configuration
│   ├── model/                      # JPA entity
│   ├── processor/                  # Item processor
│   ├── reader/                     # Item readers
│   ├── repository/                 # JPA repository
│   └── writer/                     # Item writers
├── src/main/resources/
│   └── application.yml             # Configuration
├── src/test/java/                  # Unit tests
└── k8s/
    ├── namespace.yaml              # Kubernetes namespace
    ├── secret.yaml                 # DB credentials
    ├── cronjob.yaml                # Scheduled job
    └── job.yaml                    # Manual trigger job
```

## Job Steps

This job has two steps:

1. **cleanupStep** - Soft-deletes unpublished posts older than 30 days
2. **processDeletedPostsStep** - Processes already-deleted posts (demonstrates restart behavior)

If `processDeletedPostsStep` fails and the job is restarted, `cleanupStep` will NOT re-run because it has already committed (chunk-level checkpointing).

### Restart Semantics

The job uses a date-based `JobParametersIncrementer` (see `DateJobParametersIncrementer`) that adds `run.date=YYYY-MM-DD` to every launch. This gives us:

- **Daily CronJob runs**: each calendar day is a distinct `JobInstance`.
- **Manual restart same day** (`kubectl delete job && kubectl apply` after a failure): the new pod reuses the same `JobInstance`; Spring Batch resumes from the last committed chunk, so a completed step is skipped and a failed step restarts.
- **Manual restart next day**: a fresh `JobInstance`.

The previous design used `RunIdIncrementer`, which assigned a fresh `run.id` to every launch — that made "restart" actually a fresh run and defeated Spring Batch's restart checkpointing.

## Build & Deploy

### 1. Build Maven Project

```bash
cd spring-batch-cleanup-job
mvn clean package -DskipTests
```

### 2. Build Docker Image

```bash
docker build -t cleanup-batch:1.0.0 .
docker images | grep cleanup-batch
```

### 3. Deploy to Kubernetes

```bash
# Create namespace
kubectl apply -f k8s/namespace.yaml

# Create secret (DB password)
kubectl apply -f k8s/secret.yaml

# Deploy CronJob (scheduled daily at midnight)
kubectl apply -f k8s/cronjob.yaml

# Verify deployment
kubectl get all -n batch-jobs
kubectl get cronjob -n batch-jobs
```

## Operations

### Manual Job Trigger

```bash
# Trigger cleanup job
kubectl apply -f k8s/job.yaml

# Watch job status
kubectl get job -n batch-jobs -w

# View logs
kubectl logs -n batch-jobs -l job-name=cleanup-manual
```

### CronJob Management

```bash
# Check CronJob status
kubectl get cronjob -n batch-jobs

# View CronJob details
kubectl describe cronjob cleanup-cron -n batch-jobs

# Pause CronJob (temporarily disable scheduling)
kubectl patch cronjob cleanup-cron -n batch-jobs -p '{"spec":{"suspend":true}}'

# Resume CronJob
kubectl patch cronjob cleanup-cron -n batch-jobs -p '{"spec":{"suspend":false}}'
```

### Monitoring

```bash
# List all resources in namespace
kubectl get all -n batch-jobs

# Watch pods
kubectl get pods -n batch-jobs -w

# View job history
kubectl get jobs -n batch-jobs

# Describe specific job
kubectl describe job cleanup-manual -n batch-jobs
```

### Cleanup

```bash
# Delete manual job
kubectl delete job cleanup-manual -n batch-jobs

# Delete CronJob
kubectl delete cronjob cleanup-cron -n batch-jobs

# Delete secret
kubectl delete secret db-credentials -n batch-jobs

# Delete namespace (removes all resources)
kubectl delete namespace batch-jobs
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| DB_HOST | 192.168.232.128 | PostgreSQL host |
| DB_DATABASE | testdb | Database name |
| DB_USERNAME | postgres | Database user |
| DB_PASSWORD | (from K8s Secret) | Database password |

All variables can be overridden before running scripts or applying Kubernetes manifests:

```bash
# Using environment variables
export DB_HOST=192.168.232.128
export DB_DATABASE=testdb
export DB_USERNAME=postgres
export DB_PASSWORD=your_password

# Or inline when running scripts
DB_PASSWORD=your_password ./scripts/run-and-verify.sh
```

For Kubernetes, update the values in `k8s/secret.yaml` and `k8s/job.yaml` (or `k8s/cronjob.yaml`) to match your environment.

### Batch Configuration

- **Chunk size:** 100
- **Retry limit:** 3
- **Backoff interval:** 10 seconds (fixed)
- **Hibernate JDBC batch size:** 25

### Kubernetes Resources

- **Namespace:** `batch-jobs`
- **Image:** `cleanup-batch:1.0.0`
- **Schedule:** `0 0 * * *` (daily at midnight)

## Testing

### Quick Test (Run and Verify)

```bash
# Insert test data
PGPASSWORD=<your_password> psql -h 192.168.232.128 -U postgres -d testdb -f scripts/insert-test-data.sql

# Run job and verify
./scripts/run-and-verify.sh
```

### Test Error Injection (Automated Test)

```bash
./scripts/test-error-injection.sh
```

This script runs **fully automated tests** that inject errors and verify retry/restart behavior:
1. **Step 1 Permanent Error** — injects an error in Step 1, verifies retry behavior (retries 3 times then fails)
2. **Step 2 Failure + Restart** — Step 1 succeeds and commits, Step 2 fails; on restart, Step 1 is skipped (already committed)

The script automatically rebuilds the Docker image with error injection enabled/disabled as needed.

### Test Restart Behavior (Reference Script)

```bash
./scripts/test-restart-behavior.sh
```

This script is a **reference/guidance script** — it does NOT run automated tests. Instead, it documents how Spring Batch restart semantics work and provides step-by-step manual instructions for testing:

- **TEST 1** (auto-runs): Normal job execution
- **TEST 2** (manual): Inject permanent error in Step 1, verify retries then failure
- **TEST 3** (manual): Inject transient error in Step 1, verify recovery on retry
- **TEST 4** (manual): Inject error in Step 2, restart job, verify Step 1 is skipped

For fully automated restart testing, use `test-error-injection.sh` instead.

### Manual Test Commands

```bash
# Build and deploy
mvn clean package -DskipTests && docker build -t cleanup-batch:1.0.0 .

# Run job
kubectl apply -f k8s/job.yaml

# Watch job
kubectl get job -n batch-jobs -w

# View logs
kubectl logs -n batch-jobs -l job-name=cleanup-manual
```

## Troubleshooting

### Job Failed - Connection Refused

Check PostgreSQL is listening on the correct interface:

```bash
# On PostgreSQL host
sudo sed -i "s/#listen_addresses = 'localhost'/listen_addresses = '*'/" /etc/postgresql/*/main/postgresql.conf
sudo systemctl restart postgresql

# Add pg_hba entry for Kubernetes pod network
echo "host    all             all             172.17.0.0/16            scram-sha-256" | sudo tee -a /etc/postgresql/*/main/pg_hba.conf
sudo systemctl reload postgresql
```

### Job Failed - Schema Error

If Spring Batch tables have schema mismatch, drop and recreate:

```bash
PGPASSWORD=<your_password> psql -h 192.168.232.128 -U postgres -d testdb -c "DROP TABLE IF EXISTS batch_step_execution_context, batch_step_execution, batch_job_execution_params, batch_job_execution_context, batch_job_instance, batch_job_execution CASCADE;"
```

### Check Database Tables

```bash
PGPASSWORD=<your_password> psql -h 192.168.232.128 -U postgres -d testdb -c "\dt"
```

### Verify Posts Table

```bash
PGPASSWORD=<your_password> psql -h 192.168.232.128 -U postgres -d testdb -c "SELECT COUNT(*) FROM posts WHERE is_deleted = true;"
```
