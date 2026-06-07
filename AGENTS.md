# Spring Batch Cleanup Job - Agent Instructions

## Project Overview

This is a Kubernetes-deployable Spring Batch job that soft-deletes unpublished posts older than 30 days.

## Key Files

- `src/main/java/com/example/cleanupjob/job/CleanupJobConfig.java` - Job and step configuration
- `src/main/java/com/example/cleanupjob/job/CleanupJobRunner.java` - `ApplicationRunner` that replaces Spring Boot's default; swallows `JobInstanceAlreadyCompleteException` so same-day manual run after cron completion is a graceful no-op
- `src/main/java/com/example/cleanupjob/processor/SoftDeleteProcessor.java` - Item processor
- `src/main/java/com/example/cleanupjob/reader/UnpublishedPostReader.java` - Reads unpublished posts older than 30 days
- `src/main/java/com/example/cleanupjob/reader/DeletedPostReader.java` - Reads already-deleted posts
- `src/main/java/com/example/cleanupjob/writer/BatchSoftDeleteWriter.java` - Batch soft-delete writer
- `src/main/java/com/example/cleanupjob/model/Post.java` - JPA entity
- `src/main/java/com/example/cleanupjob/repository/PostRepository.java` - Spring Data JPA repository
- `k8s/job.yaml` - Kubernetes Job manifest (manual trigger; image is sed-patched by the cd pipeline)
- `k8s/cronjob.yaml` - Kubernetes CronJob manifest (scheduled daily at midnight)
- `k8s/secret.yaml.example` - Template for `k8s/secret.yaml` (gitignored)
- `jenkins/combined-pipeline-scm.groovy` - The single pipeline script (used by all 3 Jenkins jobs)
- `scripts/jenkins-create-combined-jobs.py` - Idempotent helper to create or refresh the 3 Jenkins jobs
- `scripts/jenkins-upsert-secret-credential.py` - Idempotent helper to create/rotate Jenkins Secret-text credentials (defaults to `db-password`)
- `scripts/sql/cleanup-spring-batch-job.sql` - Row-level cleanup of Spring Batch meta state for a single job (parameterized by `:job_name`); safe to run in a shared cluster — leaves other jobs' rows and the schema itself untouched
- `scripts/lib-local.sh` - Shared helper sourced by every E2E script: `apply_local_job` does `delete + sed-on-stream + apply` so each script can deploy a locally-built image with optional `ERROR_INJECTION_*` env overrides; the file is never mutated (`sed -i` is gone)
- `scripts/setup-local.sh` - One-time cluster prep for the local-Docker flow (creates `batch-jobs` namespace + `db-credentials` Secret from `.env`); idempotent
- `scripts/e2e-cycle.sh` - The full local E2E cycle (mvn verify + docker build + setup + 4 e2e scripts); called by `mvn -Pe2e verify`
- `scripts/test-same-day-manual-run.sh` - Regression test for the `CleanupJobRunner` fix: 3 sub-tests (no-op when COMPLETED instance exists / runs normally when state is empty / no-op again after the manual run)
- `scripts/` - E2E test scripts (`run-and-verify.sh`, `test-error-injection.sh`, `test-restart-behavior.sh`, `test-same-day-manual-run.sh`); all source `lib-local.sh` and use `apply_local_job` instead of `kubectl apply -f k8s/job.yaml`

## Jenkins Integration

The CI/CD pipelines run on a local Jenkins instance.

### Prerequisites (one-time per host)

The Jenkins container, the required plugins and credentials, the container registry credential (Aliyun ACR), and minikube as the deploy target are all set up by the generic [`jenkins-docker`](https://github.com/zdry146/agent-skills/tree/main/jenkins-docker) skill — not by hand-editing this project. The skill detects what's already present, prompts before overriding, and lines up plugins + credentials + minikube + ACR into a working E2E chain:

```bash
git clone https://github.com/zdry146/agent-skills.git
cd agent-skills/jenkins-docker
JENKINS_USER=… JENKINS_TOKEN=… ./scripts/setup-env.sh   # detect → prompt → apply
JENKINS_USER=… JENKINS_TOKEN=… ./scripts/verify-e2e.sh  # confirm the chain works
```

`/home/openclaw/start-jenkins.sh` (mentioned at the end of this section) is the project's specific deployed instance of the skill's `scripts/start-jenkins.sh` pattern.

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

Two parallel paths, same source:

```bash
# --- Path 1: Local-Docker (no Jenkins, no registry) ---

# Unit + integration tests only
mvn test

# Full E2E cycle: mvn verify + docker build + setup + 4 e2e scripts
mvn -Pe2e verify

# Or run individual pieces
bash scripts/setup-local.sh                   # one-time cluster prep
mvn -B -DskipTests clean package              # build the jar
docker build -t cleanup-batch:1.0.0 .        # build the local image
bash scripts/run-and-verify.sh               # happy-path E2E
bash scripts/test-error-injection.sh         # retry + restart E2E
DEPLOY_IMAGE=cleanup-batch:1.0.0 \
  bash scripts/test-same-day-manual-run.sh   # no-op regression E2E

# --- Path 2: Jenkins CI/CD (registry image) ---
# Push to main, then click "Build Now" on spring-batch-cleanup-job-cicd
# (uses the registry image; same scripts, different transport).
```

### Resetting Spring Batch state for a single job

The `batch_*` tables are shared infrastructure. To reset state for
just this housekeeping job (e.g. to re-test the "no COMPLETED
instance yet" path), do **not** `DROP TABLE` — that affects every
job that uses Spring Batch. Use the row-level script instead:

```bash
PGPASSWORD=$DB_PASSWORD psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_DATABASE" \
    -v job_name='cleanupUnpublishedPostsJob' -v ON_ERROR_STOP=1 \
    -f scripts/sql/cleanup-spring-batch-job.sql
```

The script deletes in dependency order (step context → step execution
→ job context → job params → job execution → job instance) inside a
single transaction, so a mid-script failure leaves the DB in the
same state it started in.

## Technology Stack

- Spring Boot 4.0.5
- Spring Batch 6.0.3
- Java 21
- PostgreSQL
- JPA/Hibernate
- Docker container
- Kubernetes (batch-jobs namespace)
- Docker image: cleanup-batch:1.0.0
