# Spring Batch Cleanup Job - Kubernetes Deployment

A Spring Batch job that soft-deletes unpublished posts older than 30 days,
deployed to Kubernetes on a daily schedule.

The project supports two parallel flows for building, testing, and
deploying:

| Flow | Entry point | Image source | Used for |
|---|---|---|---|
| **Local-Docker** (debug) | `mvn -Pe2e verify` (or `bash scripts/run-and-verify.sh`) | Locally-built `cleanup-batch:1.0.0` — no registry | Day-to-day dev: rebuild fast, push nothing, watch the pod in your local cluster |
| **Jenkins CI/CD** (promotion) | `spring-batch-cleanup-job-cicd` Jenkins job | Registry-pushed `crpi-…:1.0.0` and `:latest` | Promoting a known-good build to the testing cluster |

Both flows share the same source, the same `k8s/job.yaml`, the same
test scripts, and the same Maven `verify` lifecycle. The only
difference is **where the image comes from** (local Docker daemon vs
the registry) and **who triggers the build** (`mvn` in your shell vs
Jenkins).

## Architecture

```
git push (main)  ─►  Jenkins spring-batch-cleanup-job-cicd
                          │
                          ├─ CI: mvn verify  ─► 27 tests
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
`main`. The three Jenkins jobs (`-ci`, `-cd`, `-cicd`) are
**Pipeline script** (not "Pipeline from SCM") wrapping
`jenkins/wrappers/git-fallback-wrapper.groovy`. The wrapper does
`git clone` (GitHub primary, 30s timeout, then Gitee fallback over SSH),
moves the cloned files into the workspace root, then `evaluate()`s the
real pipeline script. This gives us a working pipeline even when GitHub
is unreachable, since "Pipeline from SCM" would fail before any
fallback logic could run.

For the agent/Jenkins details (URL, credentials, job management, the
idempotent `scripts/jenkins-create-combined-jobs.py` helper, the
GitHub/Gitee SSH key setup), see [AGENTS.md](AGENTS.md).

## Project Structure

```
spring-batch-cleanup-job/
├── pom.xml                              # Maven configuration (version 1.0.0, + e2e profile)
├── Dockerfile                           # Docker image build (JRE 21)
├── AGENTS.md                            # Agent-facing notes (Jenkins, env vars)
├── README.md                            # This file
├── jenkins/
│   ├── combined-pipeline-scm.groovy     # Source of truth for the 3 Jenkins jobs (loaded by wrapper)
│   └── wrappers/
│       └── git-fallback-wrapper.groovy  # GitHub→Gitee fallback wrapper (embedded in each job's config)
├── k8s/
│   ├── namespace.yaml                   # Namespace: batch-jobs
│   ├── secret.yaml.example              # DB credentials template (gitignored: secret.yaml)
│   ├── cronjob.yaml                     # Daily at midnight
│   └── job.yaml                         # Manual trigger template (image is sed-patched by the cd pipeline
│                                        #   and by scripts/lib-local.sh::apply_local_job for local testing)
├── scripts/
│   ├── lib-local.sh                     # Shared helper: apply_local_job (delete + sed-on-stream + apply)
│   ├── setup-local.sh                   # One-time cluster prep (namespace + db-credentials Secret)
│   ├── e2e-cycle.sh                     # Full local E2E cycle (called by `mvn -Pe2e verify`)
│   ├── insert-test-data.sql             # E2E test data
│   ├── run-and-verify.sh                # E2E happy-path
│   ├── test-error-injection.sh          # E2E retry/restart
│   ├── test-restart-behavior.sh         # Reference: Spring Batch restart semantics
│   ├── test-same-day-manual-run.sh      # E2E regression: same-day manual run is a no-op when cron already completed
│   ├── jenkins-create-combined-jobs.py  # Idempotent Jenkins job manager
│   ├── jenkins-upsert-secret-credential.py # Idempotent Jenkins Secret-text credential manager
│   └── sql/
│       └── cleanup-spring-batch-job.sql # Row-level cleanup of Spring Batch meta state for one job
└── src/
    ├── main/java/com/example/cleanupjob/
    │   ├── CleanupJobApplication.java   # Spring Boot entry point
    │   ├── job/                         # Job + step config + CleanupJobRunner
    │   ├── model/                       # JPA entity
    │   ├── processor/                   # Item processor
    │   ├── reader/                      # Item readers
    │   ├── repository/                  # JPA repository
    │   └── writer/                      # Item writers
    ├── main/resources/
    │   ├── application.yml              # Configuration
    │   └── schema.sql                   # posts table DDL (reference)
    └── test/                            # Unit + integration tests (27 tests)
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
- **Manual run on a day whose cron already completed** — a graceful
  no-op. The custom `CleanupJobRunner` catches
  `JobInstanceAlreadyCompleteException` (Spring Batch's
  `startNextInstance` throws it when the latest instance for the
  parameters is `COMPLETED`), logs an info line, and exits 0. Without
  this catch the JVM exits non-zero, the pod restarts, the same error
  fires, and the pod ends up in `CrashLoopBackOff` →
  `BackoffLimitExceeded`. Covered by the regression test
  `scripts/test-same-day-manual-run.sh`.
- **Manual restart next day** — a fresh `JobInstance`.

The previous design used `RunIdIncrementer`, which assigned a fresh
`run.id` to every launch — that made "restart" actually a fresh run and
defeated Spring Batch's restart checkpointing.

## Deploying

### Path 1 — Local-Docker testing (debug, no registry)

The fastest way to iterate. Maven runs unit + integration tests, builds
the local Docker image, sets up the cluster namespace + Secret, and
runs the four E2E scripts (happy path, error injection, restart, the
same-day no-op regression) — all in one command.

Pre-reqs (one-time per machine):
- `mvn` (the project uses the wrapper at `./mvnw` if you have it, or
  the system `mvn`)
- `docker` (the local daemon builds the image)
- `kubectl` (configured to talk to a cluster — minikube, kind, or a
  remote one)
- A Postgres reachable from the cluster's pods (this project assumes
  `192.168.232.128:5432/testdb`; override via `.env`)

```bash
# One-time cluster prep
bash scripts/setup-local.sh
# (creates the batch-jobs namespace and the db-credentials Secret
#  from your .env's DB_PASSWORD; idempotent)

# Full E2E cycle (~3 min: mvn verify + docker build + 4 e2e scripts)
mvn -Pe2e verify
```

Or run individual pieces directly (the same `apply_local_job` helper
is used by every E2E script):

```bash
mvn -B clean verify                    # unit + integration tests only
mvn -B -DskipTests clean package        # build the jar only
docker build -t cleanup-batch:1.0.0 .  # build the local image
bash scripts/run-and-verify.sh         # happy-path E2E
bash scripts/test-error-injection.sh   # retry + restart with injected errors
DEPLOY_IMAGE=cleanup-batch:1.0.0 \
  bash scripts/test-same-day-manual-run.sh   # no-op regression
```

The local image is `cleanup-batch:1.0.0` and is consumed by
`scripts/lib-local.sh::apply_local_job` which sed-patches
`k8s/job.yaml`'s `__SET_BY_DEPLOY__` placeholder and flips
`imagePullPolicy: Always` to `IfNotPresent` (so k8s uses the local
daemon's image instead of trying to pull from a registry). This is
the same sed-patch pattern Jenkins' cd pipeline uses, just with a
different image.

### Path 2 — Jenkins CI/CD (promotion to testing cluster)

Use when you have a known-good build you want to promote.

**Pre-reqs** (one-time per host, before the first Jenkins build): a Jenkins Docker container with the right plugins + credentials, a container registry credential (Aliyun ACR), and minikube as the deploy target — all wired together. Use the generic [`jenkins-docker`](https://github.com/zdry146/agent-skills/tree/main/jenkins-docker) skill — it detects what's already there, prompts before overriding, and verifies the E2E chain:

The default Jenkins URL is `http://localhost:8080/`. All `scripts/jenkins-*.py` helpers honour a `JENKINS_URL` env-var override, and the full setup (credentials, jobs, URL default) lives in [AGENTS.md](AGENTS.md).

```bash
# clone the skill repo
git clone https://github.com/zdry146/agent-skills.git
cd agent-skills/jenkins-docker

JENKINS_USER=… JENKINS_TOKEN=… ./scripts/setup-env.sh   # detect → prompt → apply
JENKINS_USER=… JENKINS_TOKEN=… ./scripts/verify-e2e.sh  # confirm the chain works
```

The skill's `scripts/detect-env.sh` reports the current state; `setup-env.sh` lets you reuse / gap-fill / override per component. See the skill's `references/` for the manual ACR + minikube setup walkthroughs.

1. Push your changes to `main`.
2. In Jenkins, open `spring-batch-cleanup-job-cicd` and click **Build
   Now** (or use the `MODE=both` default build form). The job runs
   the full CI + CD pipeline.

### One-off CD without rebuilding the image

Use `spring-batch-cleanup-job-cicd` with `MODE=cd`. It skips the CI
stages and just deploys. Set `IMAGE_TAG` to either `latest` (default) or
a specific version tag like `1.0.0`.

### Deploying against a different database

The `DB_HOST` (string, default `192.168.126.133`) and `DB_DATABASE`
(string, default `testdb`) build parameters are sed-substituted into
the `__DB_HOST__` and `__DB_DATABASE__` placeholders in both
`k8s/cronjob.yaml` and `k8s/job.yaml` at apply time, so the same
committed manifests can target any cluster-reachable PostgreSQL server
and database. The `Verify` stage asserts that both the CronJob and the
Job ended up with the expected `DB_HOST` and `DB_DATABASE`.

### Just CI (no deploy)

Use `spring-batch-cleanup-job-cicd` with `MODE=ci`. Useful for
verifying a build before deciding whether to deploy.

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `192.168.232.128` | PostgreSQL host |
| `DB_DATABASE` | `testdb` | Database name |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | (required) | Database password (never hardcoded; see below) |
| `ERROR_INJECTION_STEP1` | `false` | Set `true` to inject a `SQLException` in Step 1 (for testing) |
| `ERROR_INJECTION_STEP2` | `false` | Set `true` to inject a `SQLException` in Step 2 |
| `ERROR_TYPE` | `PERMANENT` | `PERMANENT` (fail after retries) or `TRANSIENT` (recover on retry) |

`DB_PASSWORD` is **never** committed. It flows in three different ways
depending on where you are:

- **Local scripts (`scripts/*.sh`)** — fail-fast on missing env var,
  **but the scripts auto-load a gitignored `.env` from the project
  root** so you can just `bash scripts/run-and-verify.sh` with zero
  setup. Copy `.env.example` to `.env` once and edit it. An
  already-exported `DB_PASSWORD` in your shell always wins over
  `.env`. Alternative: set up `~/.pgpass` (mode `600`) and drop
  `PGPASSWORD` entirely.
- **Jenkins CD pipeline** — read from the `db-password` *Secret text*
  credential and materialized into the cluster as the `db-credentials`
  Secret at deploy time (`kubectl create secret … --dry-run | apply`).
  No `k8s/secret.yaml` is committed.
- **Manual `kubectl apply` without Jenkins** — copy
  `k8s/secret.yaml.example` to `k8s/secret.yaml`, fill in the password,
  apply it, then deploy the rest. The real `secret.yaml` is gitignored.

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

27 tests cover the reader, processor, writer, repository, restart
semantics, and the `CleanupJobRunner` no-op catch (H2 in-memory
database).

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

# Same-day manual run (the bug that CrashLoopBackOff'd cleanup-manual):
#   Test A: COMPLETED instance present  → no-op
#   Test B: row-level cleanup of state  → job actually runs
#   Test C: post-B state                → no-op again
# Auto-loads .env from project root; override the image with
#   DEPLOY_IMAGE=crpi-...:TAG bash scripts/test-same-day-manual-run.sh
bash scripts/test-same-day-manual-run.sh

# Full registry-image end-to-end: triggers the Jenkins
#   spring-batch-cleanup-job-cicd job and hard-asserts the data
#   (unpublished-old soft-deleted, control rows untouched,
#   Spring Batch metadata shows 1 COMPLETED execution).
#   Registry-image counterpart to `mvn -Pe2e verify`.
bash scripts/test-cicd-e2e.sh
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
| Manual Job in `CrashLoopBackOff` / `BackoffLimitExceeded` on the day the cron already completed | Pre-`CleanupJobRunner` image is deployed | Re-run `spring-batch-cleanup-job-cicd` with `MODE=cd` to pull the latest image (the fix lives in `CleanupJobRunner`) |

### Reset Spring Batch state for a single job

The `batch_*` tables are shared infrastructure. To reset state for just
this housekeeping job (e.g. to re-test the "no COMPLETED instance yet"
path), do **not** `DROP TABLE` — that affects every job that uses
Spring Batch. Use the row-level script instead:

```bash
# Requires DB_PASSWORD in env (see Configuration → Environment Variables)
PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_DATABASE" \
  -v job_name='cleanupUnpublishedPostsJob' -v ON_ERROR_STOP=1 \
  -f scripts/sql/cleanup-spring-batch-job.sql
```

The script deletes in dependency order (step context → step execution →
job context → job params → job execution → job instance) inside a
single transaction, so a mid-script failure leaves the DB in the same
state it started in. The tables themselves and any other job's state
are unaffected.

## Technology Stack

- Spring Boot 4.0.5
- Spring Batch 6.0.3
- Java 21
- PostgreSQL
- JPA / Hibernate
- Docker
- Kubernetes (namespace `batch-jobs`)
- Jenkins (Pipeline script with GitHub→Gitee fallback wrapper)
