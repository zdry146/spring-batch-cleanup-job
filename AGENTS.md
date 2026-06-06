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
- `jenkins/ci-pipeline.groovy` - Source of truth for the CI pipeline script (build + test + push image)
- `jenkins/deploy-pipeline.groovy` - Source of truth for the CD pipeline script (deploy image to k8s)
- `jenkins/combined-pipeline.groovy` - Source of truth for the unified CI/CD script (single script, `MODE` parameter gates stages)
- `scripts/` - E2E test scripts and Jenkins sync helpers

## Jenkins Integration

The CI and CD pipelines run on a local Jenkins instance.

| Setting | Value |
|---------|-------|
| Jenkins URL | `http://192.168.232.128:8080/` |
| Required credentials | `aliyun-docker-login`, `git-cred` |

### Jenkins jobs

| Job | Purpose | Script source | Status |
|-----|---------|---------------|--------|
| `spring-batch-cleanup-job` | CI: build, test, push image | `jenkins/ci-pipeline.groovy` | **Existing — keep** |
| `spring-batch-cleanup-job-deploy` | CD: deploy image to k8s | `jenkins/deploy-pipeline.groovy` | **Existing — keep** |
| `spring-batch-cleanup-job-ci` | CI via combined script (MODE=ci) | `jenkins/combined-pipeline.groovy` | **New — create** |
| `spring-batch-cleanup-job-cd` | CD via combined script (MODE=cd) | `jenkins/combined-pipeline.groovy` | **New — create** |

The two **existing** jobs stay as-is. The two **new** jobs share
`jenkins/combined-pipeline.groovy` and differ only in the default value
of the `MODE` parameter.

The script files in this repo and the scripts embedded in the Jenkins
jobs are **not** auto-synced. After editing any `jenkins/*.groovy`,
push the change to the running job with the helper scripts.

### Required env (export before running)

```bash
export JENKINS_USER=<jenkins username>
export JENKINS_TOKEN=<jenkins API token>   # not the password
```

Optional overrides: `JENKINS_URL`, `JOB_NAME`, `SCRIPT_PATH`,
`SHOW_DIFF` (check), `DRY_RUN` (update).

### Check drift (local vs. Jenkins)

```bash
bash scripts/jenkins-check-deploy-job.sh
# or for any other job:
JOB_NAME=spring-batch-cleanup-job bash scripts/jenkins-check-deploy-job.sh
```

- Exits `0` when identical.
- Exits `1` when they differ and prints a unified diff.
- Exits `2` on auth / network / parse error.

### Push the local script to Jenkins

```bash
# Preview the change first
DRY_RUN=true bash scripts/jenkins-update-deploy-job.sh

# Apply
bash scripts/jenkins-update-deploy-job.sh

# Confirm the round trip
bash scripts/jenkins-check-deploy-job.sh
```

The update script fetches the current `config.xml`, replaces the
`<script>` element with the local file contents, and POSTs it back. It
handles CSRF crumbs automatically.

### Workflow rule

After any edit to a `jenkins/*.groovy` file:

1. Commit and push to GitHub.
2. Run `scripts/jenkins-update-deploy-job.sh` (override `JOB_NAME` and `SCRIPT_PATH` as needed) to publish to Jenkins.
3. Run `scripts/jenkins-check-deploy-job.sh` to confirm.

### Setting up the new combined jobs

Create the two new jobs once in the Jenkins UI:

1. **New Item → Pipeline**:
   - `spring-batch-cleanup-job-ci` — paste the contents of
     `jenkins/combined-pipeline.groovy` as the inline script.
   - `spring-batch-cleanup-job-cd` — paste the same script.
2. In each job's **Build Parameters** section, set the default for
   `MODE` to `ci` or `cd` respectively (the `IMAGE_TAG` and `NAMESPACE`
   defaults from the script are fine for both).
3. After the job is created, sync the script back into the repo by
   running the check script — if it shows drift, the in-Jenkins script
   differs from the repo and you should align them.

### Alternative: switch any job to "Pipeline script from SCM"

For fully automatic sync, reconfigure the Jenkins job once:

- Definition → Pipeline script from SCM → Git
- Repository URL: `https://github.com/zdry146/spring-batch-cleanup-job.git`
- Credentials: `git-cred`
- Branch: `*/main`
- Script Path: `jenkins/<combined-pipeline|deploy-pipeline|ci-pipeline>.groovy`

After that, no manual sync is needed. The pipeline script must then
be edited to drop the redundant `Checkout` stage and the
`dir('spring-batch-cleanup-job')` wrappers (Jenkins checks out the
repo into `$WORKSPACE` as the root).

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
