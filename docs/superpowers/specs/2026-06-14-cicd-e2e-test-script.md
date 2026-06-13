# Full CI/CD End-to-End Test Script

**Date:** 2026-06-14
**Status:** Approved (brainstorming)
**Author:** (assistant)

## Goal

Add a single self-contained e2e shell script, `scripts/test-cicd-e2e.sh`, that
exercises the full Jenkins `spring-batch-cleanup-job-cicd` promotion path
against the live cluster and **hard-asserts the data afterwards**: the
unpublished-old rows must end up soft-deleted, control-group rows must be
untouched, and `batch_job_execution` must show exactly one COMPLETED row for
today.

This complements (does not replace) the existing local-Docker e2e scripts
(`scripts/e2e-cycle.sh` + its 4 sub-scripts). Those exercise the local
`docker build` + `apply_local_job` path; this new script exercises the
registry-image / Jenkins-driven path. Same source, same tests, different
transport.

## Context

The repo currently has two parallel e2e paths:

- **Local-Docker path** (no Jenkins, no registry): `scripts/e2e-cycle.sh` runs
  `mvn verify` + `docker build` + 4 e2e scripts against a local
  `cleanup-batch:1.0.0` image. The deploy goes through
  `scripts/lib-local.sh::apply_local_job` (delete + sed-on-stream + apply).
- **Jenkins CI/CD path** (registry image, k8s pull): triggered by clicking
  *Build* on `spring-batch-cleanup-job-cicd` (or by running
  `scripts/jenkins-run-cicd.py`). Runs mvn verify + docker build + docker
  push to Aliyun + k8s apply of the registry-pinned image.

Neither path currently exercises the *whole* flow end-to-end with a **data
assertion** at the end. The local scripts do check the row counts after
`apply_local_job`, but the CICD path stops at the pipeline's built-in
`Verify` stage (which only checks Job/CronJob env vars, not the pod's
runtime behaviour or the resulting data).

What's missing is a "go to a fresh cluster state, push a real registry
image, watch the pod actually do the soft-delete, then query the DB to
prove the right rows changed" script.

## Architecture

```
                +-----------------------------+
                | test-cicd-e2e.sh (new)      |
                | bash orchestrator            |
                +--------------+--------------+
                               |
   ----------------------------+--------------------------------
   |                |                |              |            |
   v                v                v              v            v
kubectl        psql           jenkins-run-    kubectl       psql
delete Job     cleanup        cicd.py         wait for      assert
               batch_*        (extended)      pod +         data +
               state                          read log      batch_*
```

**No new SQL files.** The seed and verification SQL are short enough for
inline psql heredocs (matching the pattern in `scripts/run-and-verify.sh`).
The state-cleanup step reuses the existing
`scripts/sql/cleanup-spring-batch-job.sql` primitive.

**No new lib file.** The script is self-contained except for one shim
added to `scripts/jenkins-run-cicd.py` (5 env-var overrides with
hardcoded defaults — backward compatible with the script's current
human-use mode).

## Cleanup phase

Goal: get the cluster to a known "nothing has run today" state, but **leave
the `posts` table alone** so the seed step can compare before/after.

Concretely:

1. `kubectl -n batch-jobs delete job cleanup-manual --ignore-not-found`
2. `psql -h $DB_HOST -U $DB_USERNAME -d $DB_DATABASE \
        -v job_name='cleanupUnpublishedPostsJob' -v ON_ERROR_STOP=1 \
        -f scripts/sql/cleanup-spring-batch-job.sql`
   - The existing SQL primitive; prints before/after row counts; deletes
     only rows for the named job (other jobs in shared `batch_*` tables
     are untouched).
3. **Not touched:**
   - Cron-spawned child jobs of `cleanup-cron` — the cronjob's
     `successfulJobsHistoryLimit: 3` / `failedJobsHistoryLimit: 1` already
     prunes them.
   - The `posts` table — the next phase seeds `E2E-%` rows and the
     verification step measures how those change.

## Seed phase

Inline psql heredoc. Idempotent: starts with
`DELETE FROM posts WHERE title LIKE 'E2E-%'` so re-runs don't accumulate.

Inserts 18 rows, all tagged `title LIKE 'E2E-%'`:

| Group             | Count | is_published | age | is_deleted before | expected after |
|-------------------|------:|:------------:|:---:|:-----------------:|:--------------:|
| Published old     | 3     | true         | 35d | false             | false          |
| Published new     | 3     | true         | 5d  | false             | false          |
| Unpublished new   | 3     | false        | 5d  | false             | false          |
| Unpublished old   | 7     | false        | 35d | false             | **true**       |
| Already deleted   | 2     | false        | 35d | true              | true           |

The 4 control groups (3 published old + 3 published new + 3 unpublished
new + 2 already deleted = 11 rows) are the negative test: they must be
left alone. The 7 unpublished-old rows are the positive test: they must
all become `is_deleted=true` after the job runs.

Titles are unique per row (`E2E-published-old-1..3`, `E2E-unpublished-old-1..7`,
etc.) so verification can scope its queries with `WHERE title LIKE 'E2E-%'`
without affecting any other data on the DB.

## CICD trigger phase

`scripts/jenkins-run-cicd.py` is extended (5-line change) to read
`JOB`, `MODE`, `DB_HOST`, `DB_DATABASE`, `IMAGE_TAG` from env, falling
back to the current hardcoded defaults (`JOB=spring-batch-cleanup-job-cicd`,
`MODE=both`, `IMAGE_TAG=latest`). Backward compatible — running the
script with no env still triggers the cicd build with `MODE=both`.

The new e2e script invokes it as:

```bash
JOB=spring-batch-cleanup-job-cicd \
MODE=both \
DB_HOST="$DB_HOST" DB_DATABASE="$DB_DATABASE" \
  bash scripts/jenkins-run-cicd.py
```

`jenkins-run-cicd.py` already (a) triggers the build with parameters, (b)
streams the progressive console log, (c) waits for completion, (d) exits
0 on `result=SUCCESS` and 1 otherwise. The new script just needs to call
it and propagate its exit code.

`set -e` in the bash orchestrator means a non-SUCCESS build aborts the
script before the data-assertion phase runs.

## Verify phase

Two flavours of assertion: **log assertions** (informational, count
PASS/FAIL) and **data assertions** (hard gate, exit non-zero on any
mismatch).

### Log assertions (soft)

After `kubectl wait --for=condition=complete job/cleanup-manual -n batch-jobs --timeout=120s`,
capture the pod name, get exit code (must be 0), tail the log, and check
for:

- `Step: \[cleanupStep\] executed`
- `Step: \[processDeletedPostsStep\] executed`
- `Job: \[.*cleanupUnpublishedPostsJob.*\] completed`

Same regex style as `scripts/test-same-day-manual-run.sh`. Each match
increments the `PASS` counter; a miss increments `FAIL` and prints the
expected-but-not-found line.

### Data assertions (hard gate)

Five psql queries against the `E2E-%` rows, each comparing the expected
post-run state to the actual post-run state:

```sql
-- 1. Published old: 3 rows, all still is_deleted=false
SELECT COUNT(*) FROM posts WHERE title LIKE 'E2E-published-old-%' AND is_deleted = false;
-- expected: 3

-- 2. Published new: 3 rows, all still is_deleted=false
SELECT COUNT(*) FROM posts WHERE title LIKE 'E2E-published-new-%' AND is_deleted = false;
-- expected: 3

-- 3. Unpublished new: 3 rows, all still is_deleted=false
SELECT COUNT(*) FROM posts WHERE title LIKE 'E2E-unpublished-new-%' AND is_deleted = false;
-- expected: 3

-- 4. Unpublished old: 7 rows, ALL now is_deleted=true
SELECT COUNT(*) FROM posts WHERE title LIKE 'E2E-unpublished-old-%' AND is_deleted = true;
-- expected: 7

-- 5. Already deleted: 2 rows, all still is_deleted=true
SELECT COUNT(*) FROM posts WHERE title LIKE 'E2E-already-deleted-%' AND is_deleted = true;
-- expected: 2
```

Plus one Spring Batch metadata query:

```sql
-- Exactly 1 batch_job_execution with status COMPLETED for cleanupUnpublishedPostsJob today
SELECT COUNT(*) FROM batch_job_execution je
JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id
WHERE ji.job_name = 'cleanupUnpublishedPostsJob'
  AND je.status = 'COMPLETED'
  AND je.create_time >= date_trunc('day', NOW());
-- expected: 1
```

Each query result is compared to the expected integer. A mismatch prints
`FAIL: <description>: expected <n> got <m>` and increments `FAIL`. The
final exit code is `0` iff `FAIL=0`.

## Re-runnability

The script is self-restoring and re-runnable. Each run:

1. Starts with a `DELETE FROM posts WHERE title LIKE 'E2E-%'` so
   re-runs don't accumulate
2. Cleans Spring Batch state (the script's first step), so the
   `CleanupJobRunner` no-op path doesn't kick in on a re-run
3. Ends with the cluster in a "COMPLETED JobInstance for today" state,
   identical to a normal day-end state

Re-running immediately after a successful run will see the same
unpublished-old rows *still* get soft-deleted (they were true before the
first run, are true after — wait, they're false before, the job sets
them to true; a second run finds no `is_deleted=false` unpublished-old
to find). So the seed step is the thing that makes it re-runnable.

## Failure handling

`set -e` plus the PASS/FAIL counter. Any of these is fatal (script exits 1):

- kubectl can't reach the cluster (`setup-local.sh`'s pre-flight)
- psql can't reach the DB
- `jenkins-run-cicd.py` returns non-zero (build not SUCCESS, or HTTP error)
- pod doesn't reach `Complete` within 120s
- pod exit code != 0
- any data assertion mismatches the expected count

On failure, the script prints a `=== FAILED ===` banner with the failing
check, then exits 1. **It does not attempt to clean up on failure** —
the next run's first step does that.

## File changes

- **`scripts/jenkins-run-cicd.py`** (modified, ~5 lines):
  Add env-var overrides `JOB`, `MODE`, `DB_HOST`, `DB_DATABASE`,
  `IMAGE_TAG` with the current hardcoded values as defaults. Keep
  `JENKINS_URL` env-var override that's already there.

- **`scripts/test-cicd-e2e.sh`** (new, ~200 lines):
  The full orchestration script. Bash, sources nothing, follows the
  header/style of `scripts/test-same-day-manual-run.sh`. Uses inline
  psql heredocs for seed and verification, calls
  `scripts/sql/cleanup-spring-batch-job.sql` for state cleanup, shells
  out to the extended `scripts/jenkins-run-cicd.py` for the Jenkins
  trigger.

- **`AGENTS.md`** (modified, ~5 lines):
  Add `scripts/test-cicd-e2e.sh` to the "E2E test scripts" list in the
  Project Overview, with a one-line description.

- **`README.md`** (modified, ~5 lines):
  Add a one-line mention in the Testing section, noting the difference
  from `scripts/e2e-cycle.sh` (registry path vs local-docker path).

## Acceptance criteria

- [ ] `bash scripts/test-cicd-e2e.sh` exits 0 against a fresh cluster state
      and prints `Summary: <N> passed, 0 failed`
- [ ] Running it twice in a row both succeed (re-runnability)
- [ ] If the seed SQL is broken (e.g. all rows are `is_published=true`),
      the script exits 1 with `FAIL: unpublished-old soft-deleted: expected 7 got 0`
- [ ] If the Jenkins build is set up to fail (e.g. by temporarily breaking
      the pipeline), the script exits 1 *before* running data assertions
- [ ] `scripts/jenkins-run-cicd.py` with no env still works (backward
      compatible — `MODE=both`, cicd job, no DB_HOST/DB_DATABASE params)
