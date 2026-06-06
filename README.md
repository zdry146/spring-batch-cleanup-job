# Spring Batch Cleanup Job - Kubernetes Deployment

A Spring Batch job that soft-deletes unpublished posts older than 30 days,
deployed to Kubernetes on a daily schedule. Build, test, image push, and
deploy are all driven by Jenkins — `git push` is the only manual step.

## Architecture

```
git push (main)  ─►  Jenkins spring-batch-cleanup-job-cicd
                          │
                          ├─ CI: mvn verify  ─► 26 tests
                          │      mvn help:evaluate (read pom version)
                          │      docker build + push :1.0.0 and :latest
                          │                        to Aliyun container registry
                          │
                          └─ CD: kubectl apply image-pull-secret + 4 manifests
                                kubectl set image cronjob/cleanup-cron :1.0.0
                                delete + recreate job/cleanup-manual :1.0.0
                                Verify (read back live state)
```

The pipeline source of truth is `jenkins/combined-pipeline-scm.groovy` on
`main`. The three Jenkins jobs (`-ci`, `-cd`, `-cicd`) are all
**Pipeline script from SCM** and pick up edits to that file automatically.

For the agent/Jenkins details (URL, credentials, job management, the
idempotent `scripts/jenkins-create-combined-jobs.py` helper), see
[AGENTS.md](AGENTS.md).

## Project Structure

```
spring-batch-cleanup-job/
├── pom.xml                              # Maven configuration (version 1.0.0)
├── Dockerfile                           # Docker image build (JRE 21)
├── AGENTS.md                            # Agent-facing notes (Jenkins, env vars)
├── README.md                            # This file
├── .github/workflows/ci.yml             # PR-only build + test
├── jenkins/
│   └── combined-pipeline-scm.groovy     # Source of truth for the 3 Jenkins jobs
├── k8s/
│   ├── namespace.yaml                   # Namespace: batch-jobs
│   ├── secret.yaml                      # DB credentials (dev password)
│   ├── cronjob.yaml                     # Daily at midnight
│   └── job.yaml                         # Manual trigger template
├── scripts/
│   ├── insert-test-data.sql             # E2E test data
│   ├── run-and-verify.sh                # E2E happy-path
│   ├── test-error-injection.sh          # E2E retry/restart
│   ├── test-restart-behavior.sh         # Reference: Spring Batch restart semantics
│   └── jenkins-create-combined-jobs.py  # Idempotent Jenkins job manager
└── src/
    ├── main/java/com/example/cleanupjob/
    │   ├── CleanupJobApplication.java   # Spring Boot entry point
    │   ├── job/                         # Job + step config
    │   ├── model/                       # JPA entity
    │   ├── processor/                   # Item processor
    │   ├── reader/                      # Item readers
    │   ├── repository/                  # JPA repository
    │   └── writer/                      # Item writers
    ├── main/resources/
    │   ├── application.yml              # Configuration
    │   └── schema.sql                   # posts table DDL (reference)
    └── test/                            # Unit + integration tests (26 tests)
```

## Job Steps

The Spring Batch job has two steps:

1. **cleanupStep** — reads unpublished posts older than 30 days and marks
   them `is_deleted = true`. Chunk size 100, retries `SQLException` 3 times.
2. **processDeletedPostsStep** — reads already-deleted posts and logs
   them. Chunk size 100, retries `SQLException` 2 times.

If `processDeletedPostsStep` fails and the job is restarted,
`cleanupStep` will **NOT** re-run because it has already committed at
the chunk level.

### Restart Semantics

The job uses a date-based `JobParametersIncrementer`
(`DateJobParametersIncrementer`) that adds `run.date=YYYY-MM-DD` to every
launch. This gives us:

- **Daily CronJob runs** — each calendar day is a distinct `JobInstance`.
- **Manual restart same day** (delete + re-apply after a failure) — the
  new pod reuses the same `JobInstance`; Spring Batch resumes from the
  last committed chunk, so a completed step is skipped and a failed
  step restarts.
- **Manual restart next day** — a fresh `JobInstance`.

The previous design used `RunIdIncrementer`, which assigned a fresh
`run.id` to every launch — that made "restart" actually a fresh run and
defeated Spring Batch's restart checkpointing.

## Deploying

### Recommended: trigger the Jenkins `cicd` job

1. Push your changes to `main`.
2. In Jenkins, open `spring-batch-cleanup-job-cicd` and click **Build
   Now** (or use the `MODE=both` default build form). The job runs
   the full CI + CD pipeline.

### One-off CD without rebuilding the image

Use the `spring-batch-cleanup-job-cd` job. It skips the CI stages and
just deploys. Set `IMAGE_TAG` to either `latest` (default) or a specific
version tag like `1.0.0`.

### Just CI (no deploy)

Use the `spring-batch-cleanup-job-ci` job with `MODE=ci`. Useful for
verifying a build before deciding whether to deploy.

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `192.168.232.128` | PostgreSQL host |
| `DB_DATABASE` | `testdb` | Database name |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | (empty) | Database password (set via k8s `Secret`) |
| `ERROR_INJECTION_STEP1` | `false` | Set `true` to inject a `SQLException` in Step 1 (for testing) |
| `ERROR_INJECTION_STEP2` | `false` | Set `true` to inject a `SQLException` in Step 2 |
| `ERROR_TYPE` | `PERMANENT` | `PERMANENT` (fail after retries) or `TRANSIENT` (recover on retry) |

For Kubernetes, set `DB_PASSWORD` via the `db-credentials` Secret
(see `k8s/secret.yaml`); the others are inlined in `k8s/job.yaml` and
`k8s/cronjob.yaml`.

### Batch Configuration

- **Chunk size:** 100
- **Retry limit:** 3 (Step 1) / 2 (Step 2)
- **Backoff interval:** 10 seconds (fixed)
- **Hibernate JDBC batch size:** 25

### Image

The CI job pushes two tags to the registry for every build:

```
crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com/mike-docker-registry/spring-batch-cleanup-job:1.0.0
crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com/mike-docker-registry/spring-batch-cleanup-job:latest
```

The k8s manifests pin `:latest` and the deploy job updates the live
image to whichever tag the build form selects.

## Manual Operations

### Trigger the manual Job (one-off)

```bash
# Instantiate from the template with a unique name
kubectl -n batch-jobs create job cleanup-manual-run-1 --from=job/cleanup-manual

# Watch
kubectl -n batch-jobs get pods -l job-name=cleanup-manual-run-1 -w

# Logs
kubectl -n batch-jobs logs -l job-name=cleanup-manual-run-1 -f
```

### Pause / resume the CronJob

```bash
kubectl -n batch-jobs patch cronjob cleanup-cron -p '{"spec":{"suspend":true}}'   # pause
kubectl -n batch-jobs patch cronjob cleanup-cron -p '{"spec":{"suspend":false}}'  # resume
```

### Inspect the deployed image

```bash
kubectl -n batch-jobs get cronjob cleanup-cron \
  -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[0].image}{"\n"}'
kubectl -n batch-jobs get job cleanup-manual \
  -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
```

## Testing

### Unit + integration tests

```bash
mvn test
```

26 tests cover the reader, processor, writer, repository, and restart
semantics (H2 in-memory database).

### E2E against a real cluster

The scripts under `scripts/` insert test data into PostgreSQL, apply
the k8s manifest, and verify the soft-delete happened. The error
injection is controlled by env vars in the manifest (no image rebuild
needed):

```bash
# Happy path
bash scripts/run-and-verify.sh

# Inject errors and verify retry / restart
bash scripts/test-error-injection.sh
```

## Operations

```bash
# All resources in the namespace
kubectl -n batch-jobs get all

# Recent job history
kubectl -n batch-jobs get jobs

# Pod logs
kubectl -n batch-jobs logs -l job-name=cleanup-manual -f
```

## Cleanup

```bash
# Delete the manual Job (the deploy job does this on every deploy)
kubectl -n batch-jobs delete job cleanup-manual

# Delete the CronJob
kubectl -n batch-jobs delete cronjob cleanup-cron

# Delete everything in the namespace
# (The next cicd Jenkins build recreates namespace + secret + cronjob + job)
kubectl -n batch-jobs delete all --all
```

## Troubleshooting

| Symptom | Likely cause | See |
|---|---|---|
| Build fails at `mvn -B clean verify` | Test failure | `target/surefire-reports/` |
| Build fails at `docker build` | Jenkins agent can't reach Docker socket | `AGENTS.md` → "Job workspace and Docker access" |
| Build fails at `docker push` | `aliyun-docker-login` credential wrong/expired | Update in Jenkins credentials |
| CD fails at `kubectl apply` | Minikube not running, or no kubeconfig | `kubectl get nodes` from the Jenkins host |
| Pods stuck in `ImagePullBackOff` | `aliyun-registry-cred` secret missing or wrong | Re-run the cicd job (it recreates the secret) |
| Manual Job stuck at `:latest` after a deploy | Job pod template frozen by the controller | The cicd job's `Set image tag` stage now delete+recreates it, so this self-heals on the next cicd run |

### Reset Spring Batch schema

If Spring Batch tables get into a bad state, drop and recreate:

```bash
PGPASSWORD=postgres psql -h 192.168.232.128 -U postgres -d testdb \
  -c "DROP TABLE IF EXISTS batch_step_execution_context, batch_step_execution, \
       batch_job_execution_params, batch_job_execution_context, batch_job_instance, \
       batch_job_execution CASCADE;"
```

## Technology Stack

- Spring Boot 4.0.5
- Spring Batch 6.0.3
- Java 21
- PostgreSQL
- JPA / Hibernate
- Docker
- Kubernetes (namespace `batch-jobs`)
- Jenkins (Pipeline from SCM)
