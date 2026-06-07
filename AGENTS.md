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
- `jenkins/combined-pipeline-scm.groovy` - The single pipeline script (used by all 3 Jenkins jobs)
- `scripts/jenkins-create-combined-jobs.py` - Idempotent helper to create or refresh the 3 Jenkins jobs
- `scripts/jenkins-upsert-secret-credential.py` - Idempotent helper to create/rotate Jenkins Secret-text credentials (defaults to `db-password`)
- `scripts/` - E2E test scripts

## Jenkins Integration

The CI/CD pipelines run on a local Jenkins instance.

| Setting | Value |
|---------|-------|
| Jenkins URL | `http://192.168.232.128:8080/` |
| Required credentials | `aliyun-docker-login` (username/password), `git-cred` (username/password), `db-password` (Secret text) |
| Pipeline source | `jenkins/combined-pipeline-scm.groovy` on `main` (SCM) |

### Jenkins jobs

All three jobs are **Pipeline script from SCM** pointing at
`jenkins/combined-pipeline-scm.groovy`. Any edit to that file is
picked up on the next build with no manual sync.

| Job | Default `MODE` | Purpose |
|-----|---------------|---------|
| `spring-batch-cleanup-job-ci`   | `ci`   | Build + test + push image |
| `spring-batch-cleanup-job-cd`   | `cd`   | Deploy image to k8s |
| `spring-batch-cleanup-job-cicd` | `both` | CI then CD in one run |

`MODE` choices in the build form: `ci`, `cd`, `both`. When `MODE=both`,
the tag deployed in CD is the same `IMAGE_VERSION` that CI just pushed
(read from `pom.xml`).

### Recreating or adding jobs

`scripts/jenkins-create-combined-jobs.py` is idempotent: it creates
the three jobs above if they are missing, and refreshes their `MODE`
parameter choices if they already exist. Re-run it any time.

```bash
export JENKINS_USER=admin JENKINS_TOKEN=...
python3 scripts/jenkins-create-combined-jobs.py
```

It also handles the CSRF crumb and cookie session that Jenkins
requires for `createItem` and `config.xml` POSTs.

### Managing the `db-password` credential

`k8s/secret.yaml` is **gitignored**. The CD pipeline generates the
`db-credentials` Kubernetes Secret at deploy time from the Jenkins
`db-password` *Secret text* credential. Use
`scripts/jenkins-upsert-secret-credential.py` to create or rotate it
(idempotent):

```bash
export JENKINS_USER=admin JENKINS_TOKEN=...
export CRED_SECRET='<the-postgres-password>'
python3 scripts/jenkins-upsert-secret-credential.py
```

Override `CRED_ID` / `CRED_DESC` to manage other Secret-text
credentials with the same script.

### Editing the pipeline

There is nothing to sync. Edit `jenkins/combined-pipeline-scm.groovy`,
commit, push to `main`, and the next build of any of the three jobs
runs the new script.

### Job workspace and Docker access

The Jenkins container is started by `/home/openclaw/start-jenkins.sh`,
which binds the host's `docker.sock` and adds the host's `docker` group
to the container (gid resolved at start time, so it survives future
host gid changes). This is what lets the `ci` and `both` modes run
`docker build` / `docker push` inside the agent.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| DB_HOST | 192.168.232.128 | PostgreSQL host |
| DB_DATABASE | testdb | Database name |
| DB_USERNAME | postgres | Database user |
| DB_PASSWORD | (required, never committed) | Database password — local scripts auto-load `.env` (gitignored, copy from `.env.example`) or `DB_PASSWORD` from the shell, and fail-fast otherwise; Jenkins reads from the `db-password` credential; k8s reads from the `db-credentials` Secret |
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
