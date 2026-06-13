# Full CI/CD E2E Test Script Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `scripts/test-cicd-e2e.sh` — a self-contained bash script that exercises the Jenkins `spring-batch-cleanup-job-cicd` promotion path end-to-end and **hard-asserts the data** (right rows soft-deleted, control rows untouched, Spring Batch metadata shows 1 COMPLETED execution).

**Architecture:** Bash orchestrator (~200 lines) that (1) cleans the k8s Job + Spring Batch state, (2) seeds 18 `E2E-%` rows across 5 groups via inline psql, (3) shells out to the existing `scripts/jenkins-run-cicd.py` (extended to read DB_HOST/DB_DATABASE from env) to trigger the registry-image build, (4) waits for the pod + tail-logs + asserts the data. Mirrors the structure of `scripts/test-same-day-manual-run.sh` (PASS/FAIL counter, soft log asserts + hard data asserts).

**Tech Stack:** Bash, psql, kubectl, Python (the existing `jenkins-run-cicd.py` helper), Spring Batch 6, PostgreSQL, k8s.

**Spec:** `docs/superpowers/specs/2026-06-14-cicd-e2e-test-script.md`

---

## File Structure

| File | Action | Purpose |
| --- | --- | --- |
| `scripts/jenkins-run-cicd.py` | Modify (5 lines) | Read `JOB`, `MODE`, `DB_HOST`, `DB_DATABASE`, `IMAGE_TAG` from env with current hardcoded values as defaults |
| `scripts/test-cicd-e2e.sh` | Create (~200 lines) | The full orchestrator: cleanup → seed → trigger → verify |
| `AGENTS.md` | Modify (1 line) | Mention the new script in the e2e scripts list |
| `README.md` | Modify (1 line) | Mention the new script in the Testing section |
| `docs/superpowers/plans/2026-06-14-cicd-e2e-test-script.md` | Create (this file) | Implementation plan |

The new bash script reuses (does not duplicate) two existing primitives:

- `scripts/sql/cleanup-spring-batch-job.sql` — row-level Spring Batch state cleanup (already parameterized by `:job_name`)
- `scripts/jenkins-run-cicd.py` — Jenkins trigger helper (extended in Task 1, not duplicated)

---

## Task 1: Extend `scripts/jenkins-run-cicd.py` with env-var overrides

**Files:**
- Modify: `scripts/jenkins-run-cicd.py:12-15`

The script currently hardcodes `JOB = "spring-batch-cleanup-job-cicd"` and `PARAM = "both"`. Make them env-var driven with the current hardcoded values as defaults. Also add `DB_HOST`, `DB_DATABASE`, `IMAGE_TAG` env vars that get appended to the `buildWithParameters` form body when set.

- [ ] **Step 1: Read the current file**

Open `scripts/jenkins-run-cicd.py` and confirm the top of the file looks like this (around line 12-15):

```python
JENKINS_URL = os.environ.get("JENKINS_URL", "http://localhost:8080/")
JOB = "spring-batch-cleanup-job-cicd"
PARAM = "both"
POLL_INTERVAL = 5
```

- [ ] **Step 2: Add env-var overrides + extra form params**

Replace the lines `JOB = ...` / `PARAM = ...` with this:

```python
JENKINS_URL  = os.environ.get("JENKINS_URL", "http://localhost:8080/")
JOB          = os.environ.get("JOB",         "spring-batch-cleanup-job-cicd")
PARAM        = os.environ.get("MODE",        "both")
# Extra build parameters appended to the buildWithParameters form body
# when set. Empty strings are skipped so the script's pre-existing
# call (JOB=… MODE=…) keeps working unchanged.
EXTRA_PARAMS = {
    k: os.environ[k] for k in ("DB_HOST", "DB_DATABASE", "IMAGE_TAG")
    if os.environ.get(k)
}
POLL_INTERVAL = 5
```

- [ ] **Step 3: Pass EXTRA_PARAMS into the trigger form body**

Find the `trigger` function (the one that builds the form body) and change the `data = ...` line so it includes the extra params. Look for:

```python
def trigger(opener, field, crumb):
    url = f"{JENKINS_URL}job/{JOB}/buildWithParameters"
    data = f"MODE={PARAM}".encode()
```

Change it to:

```python
def trigger(opener, field, crumb):
    url = f"{JENKINS_URL}job/{JOB}/buildWithParameters"
    body = [f"MODE={urllib.parse.quote(PARAM)}"]
    for k, v in EXTRA_PARAMS.items():
        body.append(f"{k}={urllib.parse.quote(v)}")
    data = "&".join(body).encode()
```

(The rest of the function — the `headers = ...` / `if field and crumb: headers[field] = crumb` / `req = ...` / `with opener.open(req)` lines — stays the same.)

- [ ] **Step 4: Verify the file still imports `urllib.parse`**

The existing `trigger` builds the body with f-strings only, but the new version uses `urllib.parse.quote`. Check the top of the file — `urllib.parse` should already be imported (it's used elsewhere in the file). If not, add it to the `import urllib.parse` line.

Run: `grep -n '^import' scripts/jenkins-run-cicd.py`
Expected: a line reading `import urllib.parse` (or `from urllib.parse import quote`).

- [ ] **Step 5: Smoke test — defaults still work**

Run with no env vars and confirm the script tries to trigger the cicd build with `MODE=both`:

```bash
JENKINS_USER=admin JENKINS_TOKEN=admin python3 -c "
import importlib.util
spec = importlib.util.spec_from_file_location('m', 'scripts/jenkins-run-cicd.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
print('JOB:', m.JOB); print('PARAM:', m.PARAM); print('EXTRA_PARAMS:', m.EXTRA_PARAMS)
"
```

Expected output:

```
JOB: spring-batch-cleanup-job-cicd
PARAM: both
EXTRA_PARAMS: {}
```

- [ ] **Step 6: Smoke test — env vars override correctly**

```bash
JENKINS_USER=admin JENKINS_TOKEN=admin JOB=spring-batch-cleanup-job-cd MODE=cd \
DB_HOST=10.0.0.1 DB_DATABASE=foo IMAGE_TAG=2.0.0 \
  python3 -c "
import importlib.util
spec = importlib.util.spec_from_file_location('m', 'scripts/jenkins-run-cicd.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
print('JOB:', m.JOB); print('PARAM:', m.PARAM); print('EXTRA_PARAMS:', m.EXTRA_PARAMS)
"
```

Expected output:

```
JOB: spring-batch-cleanup-job-cd
PARAM: cd
EXTRA_PARAMS: {'DB_HOST': '10.0.0.1', 'DB_DATABASE': 'foo', 'IMAGE_TAG': '2.0.0'}
```

- [ ] **Step 7: Smoke test — partial env (only DB_HOST) works**

```bash
JENKINS_USER=admin JENKINS_TOKEN=admin DB_HOST=10.0.0.1 \
  python3 -c "
import importlib.util
spec = importlib.util.spec_from_file_location('m', 'scripts/jenkins-run-cicd.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
print('EXTRA_PARAMS:', m.EXTRA_PARAMS)
"
```

Expected output:

```
EXTRA_PARAMS: {'DB_HOST': '10.0.0.1'}
```

(`DB_DATABASE` and `IMAGE_TAG` are absent because the dict-comprehension skips unset env vars.)

- [ ] **Step 8: Commit**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
git add scripts/jenkins-run-cicd.py
git -c user.name=zdry146 -c user.email=120215845@qq.com commit -m "jenkins-run-cicd.py: read JOB/MODE/DB_HOST/DB_DATABASE/IMAGE_TAG from env"
```

---

## Task 2: Create `scripts/test-cicd-e2e.sh` skeleton (header, env, helpers)

**Files:**
- Create: `scripts/test-cicd-e2e.sh`

Create the file with the header comment, `set -e`, env-var setup, helper functions, and the "===" pre-flight print. The full file is ~200 lines; this task writes the first ~50 (everything except the per-step bodies, which Tasks 3-7 fill in).

- [ ] **Step 1: Create the file with executable permissions**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
touch scripts/test-cicd-e2e.sh
chmod +x scripts/test-cicd-e2e.sh
```

- [ ] **Step 2: Write the header + env setup + helpers**

Write the following content to `scripts/test-cicd-e2e.sh`:

```bash
#!/bin/bash
# Full Jenkins CI/CD end-to-end test.
#
# This is the registry-image / Jenkins-driven counterpart to
# scripts/e2e-cycle.sh (which uses a local docker build). Same source,
# same tests, different transport: this script triggers
# spring-batch-cleanup-job-cicd on the live Jenkins instance and waits
# for the resulting pod to finish, then **hard-asserts the data**:
#
#   1. Cleanup: delete the k8s cleanup-manual Job + clear the
#      cleanupUnpublishedPostsJob rows in batch_* (existing primitive).
#   2. Seed:    insert 18 E2E-% rows across 5 groups (4 control + 1
#      target + 1 already-deleted).
#   3. Trigger: invoke the extended scripts/jenkins-run-cicd.py with
#      MODE=both and the same DB_HOST/DB_DATABASE the script was run
#      with, so the deployed pod uses the same DB this script queries.
#   4. Verify:  wait for the pod, soft-assert log lines, hard-assert
#      5 row counts + 1 batch_job_execution count. Non-zero exit on
#      any mismatch.
#
# Re-runnable: starts with `DELETE FROM posts WHERE title LIKE 'E2E-%'`
# so re-runs don't accumulate; clears Spring Batch state so the
# CleanupJobRunner no-op path doesn't kick in.
#
# Pre-conditions:
#   - kubectl context is the cluster Jenkins deploys to
#   - DB_HOST, DB_USERNAME, DB_PASSWORD, DB_DATABASE are resolvable
#     (auto-loaded from .env, or set in the shell)
#   - JENKINS_USER, JENKINS_TOKEN are set (Jenkins user with build
#     permission on spring-batch-cleanup-job-cicd)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
NAMESPACE="batch-jobs"
JOB_NAME="cleanup-manual"
SPRING_BATCH_JOB_NAME="cleanupUnpublishedPostsJob"
JENKINS_CICD_JOB="spring-batch-cleanup-job-cicd"

# Auto-load local dev credentials from .env (gitignored) so agents
# and humans can run this script with zero environment setup. An
# already-exported var in the shell always wins.
if [ -z "${DB_PASSWORD:-}" ] || [ -z "${JENKINS_USER:-}" ]; then
  if [ -f "$PROJECT_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$PROJECT_DIR/.env"
    set +a
  fi
fi
: "${DB_HOST:?DB_HOST must be set, e.g. 'export DB_HOST=...'}"
: "${DB_USERNAME:?DB_USERNAME must be set}"
: "${DB_PASSWORD:?DB_PASSWORD must be set, e.g. 'export DB_PASSWORD=...' or create .env from .env.example}"
: "${DB_DATABASE:?DB_DATABASE must be set}"
: "${JENKINS_USER:?JENKINS_USER must be set (Jenkins user with build permission on $JENKINS_CICD_JOB)}"
: "${JENKINS_TOKEN:?JENKINS_TOKEN must be set (Jenkins API token for $JENKINS_USER)}"

PASS=0
FAIL=0
pass() { echo "  OK:   $*"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $*"; FAIL=$((FAIL + 1)); }

psql_query() {
  PGPASSWORD=$DB_PASSWORD psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_DATABASE" -t -A -c "$1"
}

psql_exec() {
  PGPASSWORD=$DB_PASSWORD psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_DATABASE"
}

echo "=============================================="
echo "Full CI/CD e2e test"
echo "  cluster:    $(kubectl config current-context 2>/dev/null || echo '?')"
echo "  namespace:  $NAMESPACE"
echo "  db:         $DB_HOST / $DB_DATABASE"
echo "  jenkins:    $JENKINS_CICD_JOB (http://localhost:8080/)"
echo "=============================================="
echo ""

# === STEP 1: CLEANUP ====================================================
echo "=============================================="
echo "STEP 1: Cleanup"
echo "=============================================="

# (Filled in by Task 3)

# === STEP 2: SEED =======================================================
echo ""
echo "=============================================="
echo "STEP 2: Seed test data"
echo "=============================================="

# (Filled in by Task 4)

# === STEP 3: TRIGGER CICD ===============================================
echo ""
echo "=============================================="
echo "STEP 3: Trigger spring-batch-cleanup-job-cicd (MODE=both)"
echo "=============================================="

# (Filled in by Task 5)

# === STEP 4: VERIFY =====================================================
echo ""
echo "=============================================="
echo "STEP 4: Verify"
echo "=============================================="

# (Filled in by Tasks 6 and 7)

# === SUMMARY ============================================================
echo ""
echo "=============================================="
echo "Summary: $PASS passed, $FAIL failed"
echo "=============================================="
[ "$FAIL" = "0" ]
```

- [ ] **Step 3: Syntax check the empty-stages shell**

```bash
bash -n /home/openclaw/claudecode-workspace/spring-batch-cleanup-job/scripts/test-cicd-e2e.sh
echo "syntax OK"
```

Expected output: `syntax OK`

- [ ] **Step 4: Confirm the script runs up to the SUMMARY with no work done**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
DB_HOST=127.0.0.1 DB_USERNAME=postgres DB_PASSWORD=x DB_DATABASE=testdb \
JENKINS_USER=admin JENKINS_TOKEN=x \
  bash scripts/test-cicd-e2e.sh
```

Expected: prints the pre-flight header + the 5 `=== STEP N ===` section headers, then the `Summary: 0 passed, 0 failed` line, then exits 0. (The actual cleanup/seed/etc. bodies are still empty in this task, but the script structure runs.)

- [ ] **Step 5: Commit**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
git add scripts/test-cicd-e2e.sh
git -c user.name=zdry146 -c user.email=120215845@qq.com commit -m "test-cicd-e2e.sh: skeleton with header, env, helpers"
```

---

## Task 3: Implement the cleanup phase

**Files:**
- Modify: `scripts/test-cicd-e2e.sh` (fill in the STEP 1 section)

Replace the `# (Filled in by Task 3)` line under STEP 1 with the actual cleanup code.

- [ ] **Step 1: Edit the file**

Open `scripts/test-cicd-e2e.sh` and find this line:

```
# === STEP 1: CLEANUP ====================================================
```

Right after the `echo "STEP 1: Cleanup"` line and right before the `echo "STEP 2: Seed test data"` line, replace the `# (Filled in by Task 3)` placeholder with:

```bash
# 1a. Delete the k8s cleanup-manual Job (ignore-not-found for re-runs).
echo "--- Deleting k8s job $NAMESPACE/$JOB_NAME ---"
kubectl -n "$NAMESPACE" delete job "$JOB_NAME" --ignore-not-found

# 1b. Clear the cleanupUnpublishedPostsJob rows in batch_* via the
# existing row-level primitive. Prints before/after row counts; safe
# to run in a shared cluster (only touches rows for this job).
echo "--- Cleaning Spring Batch state for $SPRING_BATCH_JOB_NAME ---"
PGPASSWORD=$DB_PASSWORD psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_DATABASE" \
  -v job_name="$SPRING_BATCH_JOB_NAME" -v ON_ERROR_STOP=1 \
  -f "$SCRIPT_DIR/sql/cleanup-spring-batch-job.sql" | tail -4
```

- [ ] **Step 2: Syntax check**

```bash
bash -n /home/openclaw/claudecode-workspace/spring-batch-cleanup-job/scripts/test-cicd-e2e.sh && echo "syntax OK"
```

Expected: `syntax OK`

- [ ] **Step 3: Live test — cleanup runs cleanly**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
DB_HOST=192.168.126.133 DB_USERNAME=postgres DB_PASSWORD=$DB_PASSWORD \
DB_DATABASE=testdb JENKINS_USER=admin JENKINS_TOKEN=$JENKINS_TOKEN \
  bash scripts/test-cicd-e2e.sh 2>&1 | sed -n '/STEP 1:/,/STEP 2:/p'
```

Expected: prints the `Deleting k8s job batch-jobs/cleanup-manual` line (with `job.batch "cleanup-manual" deleted` or `Error from server (NotFound)` if already gone) + the 4-line tail of the cleanup-spring-batch-job.sql output showing the row counts went from N → 0.

- [ ] **Step 4: Re-run to confirm idempotency**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
DB_HOST=192.168.126.133 DB_USERNAME=postgres DB_PASSWORD=$DB_PASSWORD \
DB_DATABASE=testdb JENKINS_USER=admin JENKINS_TOKEN=$JENKINS_TOKEN \
  bash scripts/test-cicd-e2e.sh 2>&1 | sed -n '/STEP 1:/,/STEP 2:/p'
```

Expected: same shape, no errors. The `kubectl delete` shows `Error from server (NotFound)` (or just silent), and the SQL output shows 0 → 0 (state already clean from the previous run).

- [ ] **Step 5: Commit**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
git add scripts/test-cicd-e2e.sh
git -c user.name=zdry146 -c user.email=120215845@qq.com commit -m "test-cicd-e2e.sh: implement cleanup phase (delete Job + clear batch_*)"
```

---

## Task 4: Implement the seed phase

**Files:**
- Modify: `scripts/test-cicd-e2e.sh` (fill in the STEP 2 section)

Replace the `# (Filled in by Task 4)` line under STEP 2 with the inline psql heredoc.

- [ ] **Step 1: Edit the file**

Find the `# === STEP 2: SEED ===` section in `scripts/test-cicd-e2e.sh` and replace the `# (Filled in by Task 4)` placeholder with:

```bash
# 2a. Idempotent: delete any prior E2E-% rows from previous runs.
# 2b. Insert 18 rows tagged E2E-%: 4 control groups + 7 unpublished-old
#     (the actual soft-delete target) + 2 already-deleted.
echo "--- Inserting 18 E2E-% test rows ---"
psql_exec << 'SQLEOF'
-- Idempotency: clear prior E2E-% rows
DELETE FROM posts WHERE title LIKE 'E2E-%';

-- 4 control groups: must NOT be soft-deleted by the job
INSERT INTO posts (author_name, content, title, view_count, like_count, is_published, is_deleted, created_at, updated_at)
SELECT
    'E2E Author ' || i,
    'E2E control row',
    'E2E-published-old-' || i,
    0, 0, true, false,
    NOW() - INTERVAL '35 days',
    NOW() - INTERVAL '35 days'
FROM generate_series(1, 3) i;

INSERT INTO posts (author_name, content, title, view_count, like_count, is_published, is_deleted, created_at, updated_at)
SELECT
    'E2E Author ' || i,
    'E2E control row',
    'E2E-published-new-' || i,
    0, 0, true, false,
    NOW() - INTERVAL '5 days',
    NOW() - INTERVAL '5 days'
FROM generate_series(1, 3) i;

INSERT INTO posts (author_name, content, title, view_count, like_count, is_published, is_deleted, created_at, updated_at)
SELECT
    'E2E Author ' || i,
    'E2E control row',
    'E2E-unpublished-new-' || i,
    0, 0, false, false,
    NOW() - INTERVAL '5 days',
    NOW() - INTERVAL '5 days'
FROM generate_series(1, 3) i;

-- 7 unpublished-old: MUST be soft-deleted by Step 1
INSERT INTO posts (author_name, content, title, view_count, like_count, is_published, is_deleted, created_at, updated_at)
SELECT
    'E2E Author ' || i,
    'E2E target row',
    'E2E-unpublished-old-' || i,
    0, 0, false, false,
    NOW() - INTERVAL '35 days',
    NOW() - INTERVAL '35 days'
FROM generate_series(1, 7) i;

-- 2 already-deleted: untouched by Step 1, processed by Step 2
INSERT INTO posts (author_name, content, title, view_count, like_count, is_published, is_deleted, created_at, updated_at)
SELECT
    'E2E Author ' || i,
    'E2E already-deleted row',
    'E2E-already-deleted-' || i,
    0, 0, false, true,
    NOW() - INTERVAL '35 days',
    NOW() - INTERVAL '35 days'
FROM generate_series(1, 2) i;
SQLEOF

# 2c. Sanity-check the seed counts
echo "--- Sanity-checking seed counts ---"
for tbl in 'E2E-published-old' 3 'E2E-published-new' 3 'E2E-unpublished-new' 3 \
           'E2E-unpublished-old' 7 'E2E-already-deleted' 2; do
  : # handled below
done
# (Use a simpler loop: read prefix/expected pairs.)
EXPECTED_COUNTS=(
  "E2E-published-old:3"
  "E2E-published-new:3"
  "E2E-unpublished-new:3"
  "E2E-unpublished-old:7"
  "E2E-already-deleted:2"
)
for spec in "${EXPECTED_COUNTS[@]}"; do
  prefix="${spec%%:*}"
  expected="${spec##*:}"
  actual=$(psql_query "SELECT COUNT(*) FROM posts WHERE title LIKE '${prefix}-%';")
  if [ "$actual" = "$expected" ]; then
    pass "seed: ${prefix} count = ${expected}"
  else
    fail "seed: ${prefix} count expected ${expected} got ${actual}"
  fi
done
```

- [ ] **Step 2: Syntax check**

```bash
bash -n /home/openclaw/claudecode-workspace/spring-batch-cleanup-job/scripts/test-cicd-e2e.sh && echo "syntax OK"
```

Expected: `syntax OK`

- [ ] **Step 3: Live test — seed inserts the right rows**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
DB_HOST=192.168.126.133 DB_USERNAME=postgres DB_PASSWORD=$DB_PASSWORD \
DB_DATABASE=testdb JENKINS_USER=admin JENKINS_TOKEN=$JENKINS_TOKEN \
  bash scripts/test-cicd-e2e.sh 2>&1 | sed -n '/STEP 2:/,/STEP 3:/p'
```

Expected output (5 PASS lines for the sanity-check loop, all reading `seed: E2E-…-… count = N`):

```
STEP 2: Seed test data
--- Inserting 18 E2E-% test rows ---
--- Sanity-checking seed counts ---
  OK:   seed: E2E-published-old count = 3
  OK:   seed: E2E-published-new count = 3
  OK:   seed: E2E-unpublished-new count = 3
  OK:   seed: E2E-unpublished-old count = 7
  OK:   seed: E2E-already-deleted count = 2
```

(If you also see `FAIL: seed: …` lines, the INSERT or the COUNT query is wrong — fix it before continuing.)

- [ ] **Step 4: Re-run to confirm idempotency**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
DB_HOST=192.168.126.133 DB_USERNAME=postgres DB_PASSWORD=$DB_PASSWORD \
DB_DATABASE=testdb JENKINS_USER=admin JENKINS_TOKEN=$JENKINS_TOKEN \
  bash scripts/test-cicd-e2e.sh 2>&1 | sed -n '/STEP 2:/,/STEP 3:/p'
```

Expected: same 5 PASS lines. The `DELETE FROM posts WHERE title LIKE 'E2E-%'` at the top of the heredoc wipes the previous seed, so the count is still 18 fresh rows.

- [ ] **Step 5: Commit**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
git add scripts/test-cicd-e2e.sh
git -c user.name=zdry146 -c user.email=120215845@qq.com commit -m "test-cicd-e2e.sh: implement seed phase (18 E2E-% rows + sanity checks)"
```

---

## Task 5: Implement the trigger phase

**Files:**
- Modify: `scripts/test-cicd-e2e.sh` (fill in the STEP 3 section)

Replace the `# (Filled in by Task 5)` line under STEP 3 with the `jenkins-run-cicd.py` invocation.

- [ ] **Step 1: Edit the file**

Find the `# === STEP 3: TRIGGER CICD ===` section in `scripts/test-cicd-e2e.sh` and replace the `# (Filled in by Task 5)` placeholder with:

```bash
# Invoke the (extended) jenkins-run-cicd.py with MODE=both and the
# same DB_HOST/DB_DATABASE this script was run with, so the pod
# deployed by Jenkins connects to the same DB this script queries.
#
# The script's `set -e` makes a non-SUCCESS build fatal — we never
# reach STEP 4 in that case.
echo "--- Triggering $JENKINS_CICD_JOB with MODE=both ---"
JOB="$JENKINS_CICD_JOB" \
MODE="both" \
DB_HOST="$DB_HOST" \
DB_DATABASE="$DB_DATABASE" \
  bash "$SCRIPT_DIR/jenkins-run-cicd.py"
```

- [ ] **Step 2: Syntax check**

```bash
bash -n /home/openclaw/claudecode-workspace/spring-batch-cleanup-job/scripts/test-cicd-e2e.sh && echo "syntax OK"
```

Expected: `syntax OK`

- [ ] **Step 3: Verify the script reaches STEP 3 cleanly with a fresh cluster**

Before this step, clean up any leftover state from Task 3/4 (they left 18 E2E-% rows in the DB and a fresh batch_* state). Then run the full script up to the trigger:

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
DB_HOST=192.168.126.133 DB_USERNAME=postgres DB_PASSWORD=$DB_PASSWORD \
DB_DATABASE=testdb JENKINS_USER=admin JENKINS_TOKEN=$JENKINS_TOKEN \
  bash scripts/test-cicd-e2e.sh 2>&1 | tail -50
```

Expected: the build streams to stdout, the Verify stage at the end of the Jenkins pipeline asserts the env vars, then prints `Pipeline (MODE=both) succeeded` + `Finished: SUCCESS`, then the local script's STEP 4 starts (which is empty in this task — see Task 6/7).

If the build doesn't start within ~30s, double-check the CSRF cookie + Jenkins creds are working (the build #8/#9 from earlier this session are a known-good baseline).

- [ ] **Step 4: Commit**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
git add scripts/test-cicd-e2e.sh
git -c user.name=zdry146 -c user.email=120215845@qq.com commit -m "test-cicd-e2e.sh: implement trigger phase (jenkins-run-cicd.py call)"
```

---

## Task 6: Implement the verify phase — log assertions

**Files:**
- Modify: `scripts/test-cicd-e2e.sh` (fill in the start of the STEP 4 section)

Replace the `# (Filled in by Tasks 6 and 7)` line under STEP 4 with the wait-for-pod + log assertions. The data assertions come in Task 7.

- [ ] **Step 1: Edit the file**

Find the `# === STEP 4: VERIFY ===` section in `scripts/test-cicd-e2e.sh` and replace the `# (Filled in by Tasks 6 and 7)` placeholder with:

```bash
# 4a. Wait for the manual Job (created by the pipeline's Set image tag
# stage) to finish. The pipeline's Verify stage already asserts the
# env vars, so by the time we get here the spec is correct; this
# step just confirms the pod actually ran to completion.
echo "--- Waiting for k8s job $NAMESPACE/$JOB_NAME to complete ---"
kubectl -n "$NAMESPACE" wait --for=condition=complete "job/$JOB_NAME" --timeout=180s

# 4b. Capture the pod + its exit code (must be 0).
POD=$(kubectl -n "$NAMESPACE" get pods -l "job-name=$JOB_NAME" -o name | head -1)
[ -n "$POD" ] || { fail "no pod found for job $JOB_NAME"; exit 1; }
POD="${POD#pod/}"
POD_LOG=$(kubectl -n "$NAMESPACE" logs "$POD")
POD_EXIT=$(kubectl -n "$NAMESPACE" get pod "$POD" -o jsonpath='{.status.containerStatuses[0].state.terminated.exitCode}')

echo "Pod: $POD   exit: $POD_EXIT"
[ "$POD_EXIT" = "0" ] && pass "pod exited 0" || fail "pod exit was $POD_EXIT, expected 0"

# 4c. Soft-assert the expected log lines.
echo "$POD_LOG" | grep -qE 'Step: \[cleanupStep\] executed' \
  && pass "log: cleanupStep ran" \
  || fail "log: cleanupStep did NOT run (no 'Step: [cleanupStep] executed' in log)"
echo "$POD_LOG" | grep -qE 'Step: \[processDeletedPostsStep\] executed' \
  && pass "log: processDeletedPostsStep ran" \
  || fail "log: processDeletedPostsStep did NOT run"
echo "$POD_LOG" | grep -qE 'Job: \[.*cleanupUnpublishedPostsJob.*\] completed' \
  && pass "log: job completed" \
  || fail "log: job did NOT complete"

# 4d. Data assertions (filled in by Task 7).
```

- [ ] **Step 2: Syntax check**

```bash
bash -n /home/openclaw/claudecode-workspace/spring-batch-cleanup-job/scripts/test-cicd-e2e.sh && echo "syntax OK"
```

Expected: `syntax OK`

- [ ] **Step 3: Live test — full script runs through STEP 4a-c**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
DB_HOST=192.168.126.133 DB_USERNAME=postgres DB_PASSWORD=$DB_PASSWORD \
DB_DATABASE=testdb JENKINS_USER=admin JENKINS_TOKEN=$JENKINS_TOKEN \
  bash scripts/test-cicd-e2e.sh 2>&1 | tail -40
```

Expected output (last 40 lines, including the log assertions and the empty data-assertion section that follows them):

```
…
STEP 4: Verify
--- Waiting for k8s job batch-jobs/cleanup-manual to complete ---
job.batch/cleanup-manual condition met
Pod: cleanup-manual-xxxxx   exit: 0
  OK:   pod exited 0
  OK:   log: cleanupStep ran
  OK:   log: processDeletedPostsStep ran
  OK:   log: job completed
…
Summary: 4 passed, 0 failed
```

(The 4 PASS lines are the pod-exit + 3 log assertions. Data assertions follow in Task 7 — if you only see 4 PASS lines, that's expected at this point.)

- [ ] **Step 4: Commit**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
git add scripts/test-cicd-e2e.sh
git -c user.name=zdry146 -c user.email=120215845@qq.com commit -m "test-cicd-e2e.sh: implement verify phase — log assertions"
```

---

## Task 7: Implement the verify phase — data assertions (HARD GATE)

**Files:**
- Modify: `scripts/test-cicd-e2e.sh` (extend the STEP 4 section)

Replace the `# 4d. Data assertions (filled in by Task 7).` line with the 5 row-count + 1 batch_job_execution queries.

- [ ] **Step 1: Edit the file**

Find the `# 4d. Data assertions (filled in by Task 7).` line in `scripts/test-cicd-e2e.sh` and replace it with:

```bash
# 4d. Data assertions. Each check compares the post-run count to the
# expected count and increments PASS/FAIL accordingly. The final
# `[ "$FAIL" = "0" ]` at the bottom of the script is the real gate.
echo "--- Data assertions ---"

assert_count() {
  # $1=label  $2=expected  $3=SQL
  local label="$1" expected="$2" sql="$3"
  local actual
  actual=$(psql_query "$sql")
  if [ "$actual" = "$expected" ]; then
    pass "data: ${label} = ${expected}"
  else
    fail "data: ${label}: expected ${expected} got ${actual}"
  fi
}

# 1. Published old (3 rows): must STILL be is_deleted=false
assert_count "published-old not soft-deleted" 3 \
  "SELECT COUNT(*) FROM posts WHERE title LIKE 'E2E-published-old-%' AND is_deleted = false;"

# 2. Published new (3 rows): must STILL be is_deleted=false
assert_count "published-new not soft-deleted" 3 \
  "SELECT COUNT(*) FROM posts WHERE title LIKE 'E2E-published-new-%' AND is_deleted = false;"

# 3. Unpublished new (3 rows): must STILL be is_deleted=false
assert_count "unpublished-new not soft-deleted" 3 \
  "SELECT COUNT(*) FROM posts WHERE title LIKE 'E2E-unpublished-new-%' AND is_deleted = false;"

# 4. Unpublished old (7 rows): MUST ALL be is_deleted=true
assert_count "unpublished-old soft-deleted" 7 \
  "SELECT COUNT(*) FROM posts WHERE title LIKE 'E2E-unpublished-old-%' AND is_deleted = true;"

# 5. Already deleted (2 rows): must STILL be is_deleted=true (untouched)
assert_count "already-deleted untouched" 2 \
  "SELECT COUNT(*) FROM posts WHERE title LIKE 'E2E-already-deleted-%' AND is_deleted = true;"

# 6. Spring Batch metadata: exactly 1 COMPLETED execution for today
assert_count "batch_job_execution COMPLETED today" 1 \
  "SELECT COUNT(*) FROM batch_job_execution je
   JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id
   WHERE ji.job_name = '$SPRING_BATCH_JOB_NAME'
     AND je.status = 'COMPLETED'
     AND je.create_time >= date_trunc('day', NOW());"
```

- [ ] **Step 2: Syntax check**

```bash
bash -n /home/openclaw/claudecode-workspace/spring-batch-cleanup-job/scripts/test-cicd-e2e.sh && echo "syntax OK"
```

Expected: `syntax OK`

- [ ] **Step 3: Live test — full script passes end-to-end**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
DB_HOST=192.168.126.133 DB_USERNAME=postgres DB_PASSWORD=$DB_PASSWORD \
DB_DATABASE=testdb JENKINS_USER=admin JENKINS_TOKEN=$JENKINS_TOKEN \
  bash scripts/test-cicd-e2e.sh 2>&1 | tail -25
```

Expected (last 25 lines):

```
…
--- Data assertions ---
  OK:   data: published-old not soft-deleted = 3
  OK:   data: published-new not soft-deleted = 3
  OK:   data: unpublished-new not soft-deleted = 3
  OK:   data: unpublished-old soft-deleted = 7
  OK:   data: already-deleted untouched = 2
  OK:   data: batch_job_execution COMPLETED today = 1
…
Summary: 10 passed, 0 failed
```

(10 = 4 log + 6 data.) The script must exit 0. Confirm with `echo $?` right after the script finishes — should print `0`.

- [ ] **Step 4: Negative test — break the seed, confirm script fails loudly**

To prove the data gate actually catches a wrong outcome, temporarily break the seed so all 18 rows are `is_published=true` (none should get soft-deleted, so the "unpublished-old soft-deleted = 7" check should fail):

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
# Save a copy of the script, then sed-replace the seed to make all rows published.
cp scripts/test-cicd-e2e.sh /tmp/test-cicd-e2e.broken
sed -i "s/0, 0, true, false/0, 0, true, true  # BROKEN/g; s/0, 0, false, false/0, 0, false, true  # BROKEN/g" /tmp/test-cicd-e2e.broken
DB_HOST=192.168.126.133 DB_USERNAME=postgres DB_PASSWORD=$DB_PASSWORD \
DB_DATABASE=testdb JENKINS_USER=admin JENKINS_TOKEN=$JENKINS_TOKEN \
  bash /tmp/test-cicd-e2e.broken 2>&1 | tail -20
echo "exit code: $?"
rm /tmp/test-cicd-e2e.broken
```

Expected: the "data: unpublished-old soft-deleted" line prints `FAIL: data: unpublished-old soft-deleted: expected 7 got 0`, the summary says `N passed, 1 failed` (or similar), and the script exits with a non-zero code.

(Note: this negative test is one-off — do not commit the broken script. The `cp /tmp/...` keeps the broken version outside the repo. The `rm` cleans up.)

- [ ] **Step 5: Commit**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
git add scripts/test-cicd-e2e.sh
git -c user.name=zdry146 -c user.email=120215845@qq.com commit -m "test-cicd-e2e.sh: implement verify phase — data assertions (hard gate)"
```

---

## Task 8: Update AGENTS.md and README.md

**Files:**
- Modify: `AGENTS.md` (one line)
- Modify: `README.md` (one line)

- [ ] **Step 1: Find the existing e2e script list in AGENTS.md**

```bash
grep -n "e2e" /home/openclaw/claudecode-workspace/spring-batch-cleanup-job/AGENTS.md
```

Expected: a line that lists the e2e scripts (probably under a "Testing" or "Project Overview" section). Look for the existing `scripts/run-and-verify.sh`, `scripts/test-error-injection.sh`, `scripts/test-restart-behavior.sh`, `scripts/test-same-day-manual-run.sh` mentions.

- [ ] **Step 2: Add the new script to the AGENTS.md list**

In the same list, add a one-line entry. The exact wording depends on what the surrounding text looks like — follow the existing style. The new entry should communicate that this script is the **registry-image** counterpart to `e2e-cycle.sh`. For example:

```
- `scripts/test-cicd-e2e.sh` — Full CI/CD end-to-end test: triggers the Jenkins `spring-batch-cleanup-job-cicd` job (mvn verify + docker build + registry push + k8s deploy) and **hard-asserts the data** (right rows soft-deleted, control rows untouched, Spring Batch metadata shows 1 COMPLETED execution)
```

If the list lives in a sentence rather than bullets, integrate the new script into the sentence the same way the existing 4 scripts are mentioned.

- [ ] **Step 3: Add the new script to the README.md "Testing" section**

```bash
grep -n "scripts/run-and-verify\|scripts/test-error-injection\|scripts/test-restart\|scripts/test-same-day" /home/openclaw/claudecode-workspace/spring-batch-cleanup-job/README.md
```

Find the "Testing" section and add the new script in the same style. A one-liner is enough — for example:

```
- `bash scripts/test-cicd-e2e.sh` — full registry-image end-to-end: triggers the Jenkins `cicd` job and **hard-asserts the data** (unpublished-old soft-deleted, control rows untouched, Spring Batch metadata shows 1 COMPLETED execution)
```

- [ ] **Step 4: Commit**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
git add AGENTS.md README.md
git -c user.name=zdry146 -c user.email=120215845@qq.com commit -m "docs: mention test-cicd-e2e.sh in AGENTS.md and README.md"
```

---

## Task 9: Final acceptance — run the full script twice

**Files:** (no code changes; this task only runs the script and verifies the acceptance criteria from the spec)

- [ ] **Step 1: Run the full script against a fresh cluster state**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
DB_HOST=192.168.126.133 DB_USERNAME=postgres DB_PASSWORD=$DB_PASSWORD \
DB_DATABASE=testdb JENKINS_USER=admin JENKINS_TOKEN=$JENKINS_TOKEN \
  bash scripts/test-cicd-e2e.sh 2>&1 | tail -20
echo "exit code: $?"
```

Expected: `Summary: 10 passed, 0 failed`, exit code `0`.

- [ ] **Step 2: Run it again immediately to confirm re-runnability**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
DB_HOST=192.168.126.133 DB_USERNAME=postgres DB_PASSWORD=$DB_PASSWORD \
DB_DATABASE=testdb JENKINS_USER=admin JENKINS_TOKEN=$JENKINS_TOKEN \
  bash scripts/test-cicd-e2e.sh 2>&1 | tail -20
echo "exit code: $?"
```

Expected: same `Summary: 10 passed, 0 failed`, exit code `0`. The seed step's `DELETE FROM posts WHERE title LIKE 'E2E-%'` + the Spring Batch state cleanup at the start make this work.

- [ ] **Step 3: Push the commits to origin/main**

```bash
cd /home/openclaw/claudecode-workspace/spring-batch-cleanup-job
git log --oneline -10
git push origin main
```

Expected: the 8 new commits from Tasks 1-8 are pushed to `origin/main`. The git log should show, in order: spec commit (from brainstorming) + 8 plan commits (one per task).

---

## Acceptance criteria mapping (from the spec)

- [x] **AC1: exits 0 against a fresh cluster state and prints `Summary: <N> passed, 0 failed`** — verified in Task 9, Step 1
- [x] **AC2: running it twice in a row both succeed** — verified in Task 9, Step 2
- [x] **AC3: if the seed SQL is broken, the script exits 1 with `FAIL: …`** — verified in Task 7, Step 4
- [x] **AC4: if the Jenkins build fails, the script exits 1 before running data assertions** — implicit in `set -e` + the trigger phase. (To verify: temporarily break `jenkins/combined-pipeline-scm.groovy` to throw, run the script, see it exit 1 at STEP 3, then revert the pipeline change. This is a one-off manual check, not a committed test.)
- [x] **AC5: `scripts/jenkins-run-cicd.py` with no env still works** — verified in Task 1, Step 5
