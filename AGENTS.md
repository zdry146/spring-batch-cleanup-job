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
- `jenkins/combined-pipeline-scm.groovy` - The single pipeline script (used by all 3 Jenkins jobs via `evaluate()` from the wrapper)
- `jenkins/wrappers/git-fallback-wrapper.groovy` - GitHub→Gitee fallback wrapper embedded in each job config; loads `combined-pipeline-scm.groovy` after a successful git clone
- `scripts/jenkins-create-combined-jobs.py` - Idempotent helper to create or refresh the 3 Jenkins jobs (uses the wrapper script)
- `scripts/jenkins-upsert-secret-credential.py` - Idempotent helper to create/rotate Jenkins Secret-text credentials (defaults to `db-password`)
- `scripts/sql/cleanup-spring-batch-job.sql` - Row-level cleanup of Spring Batch meta state for a single job (parameterized by `:job_name`); safe to run in a shared cluster — leaves other jobs' rows and the schema itself untouched
- `scripts/lib-local.sh` - Shared helper sourced by every E2E script: `apply_local_job` does `delete + sed-on-stream + apply` so each script can deploy a locally-built image with optional `ERROR_INJECTION_*` env overrides; the file is never mutated (`sed -i` is gone)
- `scripts/setup-local.sh` - One-time cluster prep for the local-Docker flow (creates `batch-jobs` namespace + `db-credentials` Secret from `.env`); idempotent
- `scripts/e2e-cycle.sh` - The full local E2E cycle (mvn verify + docker build + setup + 4 e2e scripts); called by `mvn -Pe2e verify`
- `scripts/test-cicd-e2e.sh` - Full CI/CD end-to-end test: triggers the Jenkins `spring-batch-cleanup-job-cicd` job (mvn verify + docker build + registry push + k8s deploy) and **hard-asserts the data** (right rows soft-deleted, control rows untouched, Spring Batch metadata shows 1 COMPLETED execution). Registry-image counterpart to `e2e-cycle.sh`.
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
| Jenkins URL | `http://localhost:8080/` (default; override per-call with `JENKINS_URL=…` env var on the `scripts/jenkins-*.py` helpers) |
| Required credentials | `aliyun-docker-login` (username/password), `git-cred` (username/password), `db-password` (Secret text) |
| Pipeline source | `jenkins/combined-pipeline-scm.groovy` on `main` (SCM) |

### Jenkins jobs

The three jobs (`spring-batch-cleanup-job-ci`, `-cd`, `-cicd`) are
**Pipeline script** (NOT "from SCM") wrapping
[`jenkins/wrappers/git-fallback-wrapper.groovy`](jenkins/wrappers/git-fallback-wrapper.groovy).

**Why not "Pipeline from SCM"?** When Jenkins loads a pipeline from
SCM, it has to fetch the Jenkinsfile from GitHub at job-start time.
If GitHub is down, the pipeline can't even start — there's no chance
to fall back. The wrapper inverts this: it runs as a Groovy script
embedded directly in the job config, tries to `git clone` the repo
from GitHub first (30s timeout), and falls back to Gitee (SSH) on
failure. After the checkout, it `evaluate()`s the real pipeline
script at `jenkins/combined-pipeline-scm.groovy` from the workspace.

**Three things the wrapper handles that naive "Pipeline from SCM"
can't:**

1. **GitHub→Gitee fallback** — `git clone` with timeout, then
   fallback to `git@gitee.com:zdry146/spring-batch-cleanup-job.git`.
   The fallback uses SSH key `~/.ssh/gitee_key` (separate from
   `~/.ssh/github_key`) configured in `~/.ssh/config` per-host.
2. **Move cloned files to workspace root** — inner Jenkinsfile
   expects `pom.xml` in the workspace root, not a subdirectory.
   Uses `bash` (not `dash`) and `cp -a .cloned-tmp/. .` to overwrite.
3. **`agent any` → `agent none` substitution** — without this, the
   inner pipeline allocates a fresh empty workspace (`cicd@2`),
   ignoring the wrapper's already-cloned repo. Substituting
   `agent none` makes the inner stages reuse the outer `node {}`
   context.

**Script approval caveat:** Whenever the wrapper's text changes
(e.g. SSH URL, agent substitution logic), Jenkins requires manual
approval via `/scriptApproval` (UI) before builds will run. The
Stapler-bound `approveScript` RPC returns HTTP 200 but does NOT
actually approve — always do this in the UI.

**Reference: bringing up a wrapper-based job on a new host**

```bash
# 1. Set up SSH keys for GitHub + Gitee on the Jenkins host
ssh-keygen -t ed25519 -C "openclaw@local" -f ~/.ssh/github_key
ssh-keygen -t ed25519 -C "openclaw@local-gitee" -f ~/.ssh/gitee_key
# Paste github_key.pub → github.com/.../keys
# Paste gitee_key.pub  → gitee.com/.../ssh_keys
cat >> ~/.ssh/config <<'EOF'
Host github.com
    IdentityFile ~/.ssh/github_key
    User git
    IdentitiesOnly yes
Host gitee.com
    IdentityFile ~/.ssh/gitee_key
    User git
    IdentitiesOnly yes
EOF
chmod 600 ~/.ssh/config

# 2. Generate the wrapper config XML (script body is in
#    jenkins/wrappers/git-fallback-wrapper.groovy) and POST to Jenkins
python3 scripts/jenkins-create-combined-jobs.py
# That helper creates the job from the wrapper script source.

# 3. Trigger once → approve in UI (one-time per wrapper text change)
#    Manage Jenkins → In-process Script Approval → Approve
```

See [`scripts/jenkins-create-combined-jobs.py`](scripts/jenkins-create-combined-jobs.py)
for the automated job-creation flow that handles the CSRF crumb,
cookie session, XML escaping, and the wrapper script upload in one
shot.

| Job | Default `MODE` | Purpose |
|-----|---------------|---------|
| `spring-batch-cleanup-job-ci`   | `ci`   | Build + test + push image |
| `spring-batch-cleanup-job-cd`   | `cd`   | Deploy image to k8s |
| `spring-batch-cleanup-job-cicd` | `both` | CI then CD in one run |

`MODE` choices in the build form: `ci`, `cd`, `both`. When `MODE=both`,
the tag deployed in CD is the same `IMAGE_VERSION` that CI just pushed
(read from `pom.xml`).

### Per-deploy database host and database name

`DB_HOST` (string, default `192.168.126.133`) and `DB_DATABASE`
(string, default `testdb`) are both build parameters. The CD pipeline
sed-substitutes the `__DB_HOST__` and `__DB_DATABASE__` placeholders
in `k8s/cronjob.yaml` and `k8s/job.yaml` with these values at apply
time, so the same committed manifests can target any cluster-reachable
PostgreSQL server and database. `Verify` stage asserts that both the
CronJob and the Job ended up with the expected `DB_HOST`,
`DB_DATABASE` (and image).

### Recreating or adding jobs

`scripts/jenkins-create-combined-jobs.py` is idempotent: it creates
the three jobs above if they are missing, and refreshes their `MODE`
choices + backfills any missing `DB_HOST` / `DB_DATABASE` string
parameters if they already exist. Re-run it any time after the
pipeline script changes.

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

### Editing the wrapper

The wrapper at `jenkins/wrappers/git-fallback-wrapper.groovy` is
embedded in each job's Jenkins config (not fetched from SCM). After
editing:

1. Run `scripts/jenkins-create-combined-jobs.py` to push the new
   wrapper into all three job configs.
2. Trigger one build → the script will fail with
   `UnapprovedUsageException` until you Approve in
   `/scriptApproval` (this is a one-time approval per unique script
   text — see the "Script approval caveat" above).
3. Re-trigger; subsequent builds run without re-approval until the
   wrapper text changes again.

### Job workspace and Docker access

The Jenkins container is started by `/home/openclaw/start-jenkins.sh`,
which binds the host's `docker.sock` and adds the host's `docker` group
to the container (gid resolved at start time, so it survives future
host gid changes). This is what lets the `ci` and `both` modes run
`docker build` / `docker push` inside the agent.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| DB_HOST | 192.168.232.128 (local e2e scripts) / 192.168.126.133 (Jenkins CD build param default) | PostgreSQL host; the Jenkins `DB_HOST` build parameter is sed-substituted into `k8s/cronjob.yaml` and `k8s/job.yaml` at deploy time, and `apply_local_job` accepts a 5th positional arg to override it for local E2E |
| DB_DATABASE | testdb (matches `${DB_DATABASE:testdb}` in `src/main/resources/application.yml`) | PostgreSQL database name; the Jenkins `DB_DATABASE` build parameter is sed-substituted into both manifests at deploy time, and `apply_local_job` accepts a 6th positional arg to override it for local E2E |
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
