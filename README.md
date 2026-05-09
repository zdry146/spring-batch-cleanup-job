# Spring Batch Cleanup Job - Kubernetes Deployment

Kubernetes-deployable Spring Batch job for soft-deleting unpublished posts older than 30 days.

## Project Structure

```
spring-batch-cleanup-job/
├── pom.xml                          # Maven configuration
├── Dockerfile                       # Docker image build
├── scripts/                         # Test scripts
│   ├── insert-test-data.sql        # Insert test data
│   ├── run-and-verify.sh           # Run job and verify
│   └── test-restart-behavior.sh   # Test restart semantics
├── src/main/
│   ├── java/com/example/cleanupjob/
│   │   ├── CleanupJobApplication.java
│   │   ├── model/Post.java
│   │   ├── repository/PostRepository.java
│   │   ├── reader/
│   │   │   ├── UnpublishedPostReader.java
│   │   │   └── DeletedPostReader.java
│   │   ├── processor/SoftDeleteProcessor.java
│   │   ├── writer/BatchSoftDeleteWriter.java
│   │   └── job/CleanupJobConfig.java
│   └── resources/
│       ├── application.yml          # Configuration
│       └── schema.sql              # DB schema reference
└── k8s/
    ├── namespace.yaml               # Kubernetes namespace
    ├── secret.yaml                  # DB credentials secret
    ├── cronjob.yaml                 # Scheduled job (midnight daily)
    └── job.yaml                     # Manual trigger job
```

## Job Steps

This job has two steps:

1. **cleanupStep** - Soft-deletes unpublished posts older than 30 days
2. **processDeletedPostsStep** - Processes already-deleted posts (demonstrates restart behavior)

If `processDeletedPostsStep` fails and the job is restarted, `cleanupStep` will NOT re-run because it has already committed (chunk-level checkpointing).

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
| DB_USERNAME | postgres | Database user |
| DB_PASSWORD | (from K8s Secret) | Database password |

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

### Test Restart Behavior

```bash
./scripts/test-restart-behavior.sh
```

This demonstrates Spring Batch restart semantics:
- Step 1 commits its data
- If Step 2 fails and we restart, Step 1 does NOT re-run

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
