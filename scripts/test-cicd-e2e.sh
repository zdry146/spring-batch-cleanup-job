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
  PGPASSWORD=$DB_PASSWORD psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_DATABASE" -v ON_ERROR_STOP=1
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

# 1a. Delete the k8s cleanup-manual Job (ignore-not-found for re-runs).
echo "--- Deleting k8s job $NAMESPACE/$JOB_NAME ---"
kubectl -n "$NAMESPACE" delete job "$JOB_NAME" --ignore-not-found

# 1b. Clear the cleanupUnpublishedPostsJob rows in batch_* via the
# existing row-level primitive. Prints before/after row counts; safe
# to run in a shared cluster (only touches rows for this job).
echo "--- Cleaning Spring Batch state for $SPRING_BATCH_JOB_NAME ---"
PGPASSWORD=$DB_PASSWORD psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_DATABASE" \
  -v job_name="$SPRING_BATCH_JOB_NAME" \
  -f "$SCRIPT_DIR/sql/cleanup-spring-batch-job.sql" | tail -4

# === STEP 2: SEED =======================================================
echo ""
echo "=============================================="
echo "STEP 2: Seed test data"
echo "=============================================="

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

# === STEP 3: TRIGGER CICD ===============================================
echo ""
echo "=============================================="
echo "STEP 3: Trigger spring-batch-cleanup-job-cicd (MODE=both)"
echo "=============================================="

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
  "$SCRIPT_DIR/jenkins-run-cicd.py"

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